package core.global.entity.image.service.impl;

import core.global.config.QuerydslConfig;
import core.global.entity.image.S3Props;
import core.global.entity.image.repository.ImageOperationConsumedMessageRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageCopyExecutor;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.ImageOperationBatchTransactionService;
import core.global.entity.image.service.ImagePersistenceTransactionService;
import core.global.entity.image.service.ImageOperationRecoveryService;
import core.global.entity.image.service.ImageOperationService;
import core.global.entity.image.service.ImageOperationStepService;
import core.global.entity.image.service.ImageStorageClient;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Tag("performance")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=off"
})
@Import({
        ImageCopyExecutor.class,
        ImageOperationService.class,
        ImageOperationStepService.class,
        ImageOperationRecoveryService.class,
        ImageOperationBatchService.class,
        ImageOperationBatchTransactionService.class,
        ImagePersistenceTransactionService.class,
        QuerydslConfig.class,
        PostImageObjectStorageBenchmarkTest.ObjectStorageBenchmarkConfig.class
})
class PostImageObjectStorageBenchmarkTest {

    private static final long BENCHMARK_POST_ID = -3L;
    private static final List<Integer> IMAGE_COUNTS = List.of(1, 5, 10, 20);
    private static final String BENCHMARK_PREFIX = "temp/codex-post-benchmark/";
    private static final byte[] BENCHMARK_OBJECT_CONTENT = BenchmarkJpegFactory.createEightMiBJpeg();

    @Autowired
    private ImageOperationBatchService batchService;
    @Autowired
    private ImageCopyExecutor imageCopyExecutor;
    @Autowired
    private ImageRepository imageRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private ImagePersistenceTransactionService persistenceTransactionService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private core.global.entity.image.service.ImageUploadSessionService imageUploadSessionService;
    @Autowired
    private S3Client s3Client;

    @MockitoBean
    private ImageStorageClient storageClient;
    @MockitoBean
    private ImageOperationConsumedMessageRepository consumedMessageRepository;

    private final List<String> objectKeysToDelete = new ArrayList<>();
    private Statistics statistics;
    private PostImageServiceImpl postImageService;
    private String bucket;

    @BeforeEach
    void setUp() {
        assumeTrue(enabled(), "Run with POST_IMAGE_OBJECT_STORAGE_BENCHMARK=true");

        bucket = requiredEnv("NCP_BUCKET_NAME");
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        when(storageClient.isStagingKey(anyString()))
                .thenAnswer(invocation -> invocation.<String>getArgument(0).startsWith("temp/"));
        when(storageClient.isDefaultUrlOrKey(anyString())).thenReturn(false);

        ReflectionTestUtils.setField(imageCopyExecutor, "bucket", bucket);
        ReflectionTestUtils.setField(batchService, "imageOperationRabbitEnabled", true);

        postImageService = new PostImageServiceImpl(
                s3Client,
                imageRepository,
                storageClient,
                batchService,
                persistenceTransactionService,
                org.mockito.Mockito.mock(core.global.entity.image.service.ImageUploadSessionService.class),
                org.mockito.Mockito.mock(S3Presigner.class),
                org.mockito.Mockito.mock(S3Props.class),
                org.mockito.Mockito.mock(ApplicationEventPublisher.class)
        );
        ReflectionTestUtils.setField(postImageService, "bucket", bucket);
        ReflectionTestUtils.setField(postImageService, "endPoint", "https://kr.object.ncloudstorage.com");
        ReflectionTestUtils.setField(postImageService, "cdnBaseUrl", "https://benchmark.invalid");
    }

    @AfterEach
    void tearDown() {
        if (!enabled()) {
            return;
        }
        cleanupBenchmarkRows();
        deleteBenchmarkObjects();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void measurePostImageServiceWithRealObjectStorageCopy() {
        System.out.println();
        System.out.println("imageCount,elapsedMs,preparedStatements,hibernateTransactions");

        for (int imageCount : IMAGE_COUNTS) {
            cleanupBenchmarkRows();
            deleteBenchmarkObjects();

            List<String> stagingKeys = prepareStagingObjects(imageCount);
            objectKeysToDelete.addAll(IntStream.range(0, imageCount)
                    .mapToObj(index -> "posts/%d/%03d_%s".formatted(
                            BENCHMARK_POST_ID,
                            index,
                            basename(stagingKeys.get(index))
                    ))
                    .toList());
            statistics.clear();

            long startedAt = System.nanoTime();
            postImageService.savePostImages(BENCHMARK_POST_ID, stagingKeys);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            System.out.printf(
                    "%d,%d,%d,%d%n",
                    imageCount,
                    elapsedMillis,
                    statistics.getPrepareStatementCount(),
                    statistics.getTransactionCount()
            );

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from image where image_type = 'POST' and related_id = ?",
                    Integer.class,
                    BENCHMARK_POST_ID
            )).isEqualTo(imageCount);
        }
    }

    private List<String> prepareStagingObjects(int imageCount) {
        String runId = UUID.randomUUID().toString();
        List<String> keys = IntStream.range(0, imageCount)
                .mapToObj(index -> BENCHMARK_PREFIX + runId + "/" + index + ".jpg")
                .toList();
        for (String key : keys) {
            s3Client.putObject(
                    request -> request.bucket(bucket).key(key).contentType("image/jpeg"),
                    RequestBody.fromBytes(BENCHMARK_OBJECT_CONTENT)
            );
        }
        objectKeysToDelete.addAll(keys);
        return keys;
    }

    private void cleanupBenchmarkRows() {
        jdbcTemplate.update("delete from image where image_type = 'POST' and related_id = ?", BENCHMARK_POST_ID);
        jdbcTemplate.update("""
                delete from image_operation_publish_outbox
                where operation_id in (
                    select operation_id from image_operation where owner_id = ?
                )
                """, BENCHMARK_POST_ID);
        jdbcTemplate.update("""
                delete from image_operation_step
                where operation_id in (
                    select operation_id from image_operation where owner_id = ?
                )
                """, BENCHMARK_POST_ID);
        jdbcTemplate.update("delete from image_operation where owner_id = ?", BENCHMARK_POST_ID);
    }

    private void deleteBenchmarkObjects() {
        if (objectKeysToDelete.isEmpty()) {
            return;
        }
        s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder()
                        .objects(objectKeysToDelete.stream()
                                .distinct()
                                .map(key -> ObjectIdentifier.builder().key(key).build())
                                .toList())
                        .build())
                .build());
        objectKeysToDelete.clear();
    }

    private String basename(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    private static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("POST_IMAGE_OBJECT_STORAGE_BENCHMARK"));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    @TestConfiguration
    static class ObjectStorageBenchmarkConfig {

        @Bean
        S3Client benchmarkS3Client() {
            if (!enabled()) {
                return org.mockito.Mockito.mock(S3Client.class);
            }
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    requiredEnv("NCP_ACCESS_KEY"),
                    requiredEnv("NCP_SECRET_KEY")
            );
            return S3Client.builder()
                    .region(Region.of("kr-standard"))
                    .endpointOverride(URI.create("https://kr.object.ncloudstorage.com"))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                    .build();
        }
    }
}
