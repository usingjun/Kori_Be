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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;
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
        ImageOperationService.class,
        ImageOperationStepService.class,
        ImageOperationRecoveryService.class,
        ImageOperationBatchService.class,
        ImageOperationBatchTransactionService.class,
        ImagePersistenceTransactionService.class,
        QuerydslConfig.class
})
class PostImageServiceDbBenchmarkTest {

    private static final long BENCHMARK_POST_ID = -2L;
    private static final List<Integer> IMAGE_COUNTS = List.of(1, 5, 10, 20);

    @Autowired
    private ImageOperationBatchService batchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private ImageRepository imageRepository;
    @Autowired
    private ImagePersistenceTransactionService persistenceTransactionService;

    @MockitoBean
    private ImageCopyExecutor imageCopyExecutor;
    @MockitoBean
    private ImageStorageClient storageClient;
    @MockitoBean
    private ImageOperationConsumedMessageRepository consumedMessageRepository;

    private Statistics statistics;
    private PostImageServiceImpl postImageService;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("POST_IMAGE_DB_BENCHMARK")),
                "Run with POST_IMAGE_DB_BENCHMARK=true");

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        when(imageCopyExecutor.copy(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return new ImageCopyExecutor.ImageCopyResult(
                            invocation.getArgument(1),
                            "benchmark-etag"
                    );
                });
        when(storageClient.isStagingKey(anyString())).thenReturn(true);
        when(storageClient.isDefaultUrlOrKey(anyString())).thenReturn(false);

        postImageService = new PostImageServiceImpl(
                org.mockito.Mockito.mock(S3Client.class),
                imageRepository,
                storageClient,
                batchService,
                persistenceTransactionService,
                org.mockito.Mockito.mock(S3Presigner.class),
                org.mockito.Mockito.mock(S3Props.class),
                org.mockito.Mockito.mock(ApplicationEventPublisher.class)
        );
        ReflectionTestUtils.setField(postImageService, "bucket", "benchmark-bucket");
        ReflectionTestUtils.setField(postImageService, "endPoint", "https://object.example.com");
        ReflectionTestUtils.setField(postImageService, "cdnBaseUrl", "https://cdn.example.com");
    }

    @AfterEach
    void tearDown() {
        cleanupBenchmarkRows();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void measurePostImageServiceWithCurrentOperationStructure() {
        System.out.println();
        System.out.println("imageCount,elapsedMs,preparedStatements,hibernateTransactions");

        for (int imageCount : IMAGE_COUNTS) {
            cleanupBenchmarkRows();
            statistics.clear();

            List<String> stagingKeys = IntStream.range(0, imageCount)
                    .mapToObj(index -> "temp/post-benchmark-" + index + ".jpg")
                    .toList();

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
}
