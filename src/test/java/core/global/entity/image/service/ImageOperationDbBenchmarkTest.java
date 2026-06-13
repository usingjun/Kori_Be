package core.global.entity.image.service;

import core.global.config.QuerydslConfig;
import core.global.entity.image.repository.ImageOperationConsumedMessageRepository;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        QuerydslConfig.class
})
class ImageOperationDbBenchmarkTest {

    private static final List<Integer> IMAGE_COUNTS = List.of(1, 5, 10, 20);

    @Autowired
    private ImageOperationBatchService batchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private ImageCopyExecutor imageCopyExecutor;
    @MockitoBean
    private ImageStorageClient storageClient;
    @MockitoBean
    private ImageOperationConsumedMessageRepository consumedMessageRepository;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("IMAGE_OPERATION_DB_BENCHMARK")),
                "Run with IMAGE_OPERATION_DB_BENCHMARK=true");
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        when(imageCopyExecutor.copy(anyString(), anyString()))
                .thenAnswer(invocation -> new ImageCopyExecutor.ImageCopyResult(
                        invocation.getArgument(1),
                        "benchmark-etag"
                ));
        when(storageClient.isDefaultUrlOrKey(anyString())).thenReturn(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void measureCurrentPerImageOperationStructure() {
        System.out.println();
        System.out.println("imageCount,elapsedMs,preparedStatements,hibernateTransactions");
        for (int imageCount : IMAGE_COUNTS) {
            cleanupBenchmarkRows();
            statistics.clear();

            long startedAt = System.nanoTime();
            List<ImageOperationBatchService.TrackedCopy> copies = IntStream.range(0, imageCount)
                    .mapToObj(index -> batchService.copy(
                            ImageOperationType.CREATE_POST_IMAGES,
                            ImageOperationOwnerType.POST,
                            -1L,
                            "temp/benchmark-" + index + ".jpg",
                            "posts/-1/%03d_benchmark.jpg".formatted(index)
                    ))
                    .toList();
            batchService.scheduleCleanup(ImageOperationOwnerType.POST, -1L, copies, List.of());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            System.out.printf(
                    "%d,%d,%d,%d%n",
                    imageCount,
                    elapsedMillis,
                    statistics.getPrepareStatementCount(),
                    statistics.getTransactionCount()
            );

            assertThat(copies).hasSize(imageCount);
        }
    }

    private void cleanupBenchmarkRows() {
        jdbcTemplate.update("""
                delete from image_operation_publish_outbox
                where operation_id in (
                    select operation_id from image_operation where owner_id = -1
                )
                """);
        jdbcTemplate.update("""
                delete from image_operation_step
                where operation_id in (
                    select operation_id from image_operation where owner_id = -1
                )
                """);
        jdbcTemplate.update("delete from image_operation where owner_id = -1");
    }
}
