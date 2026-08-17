package core.domain.notification.batch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

@JdbcTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:notification-cleanup-batch-test-schema.sql,classpath:db/migration/V202608170100__create_spring_batch_metadata.sql,classpath:db/migration/V202608170200__add_notification_cleanup_index.sql",
        "spring.batch.job.enabled=false",
        "spring.batch.jdbc.initialize-schema=never"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(BatchAutoConfiguration.class)
@Import({
        NotificationCleanupJobConfig.class,
        NotificationCleanupItemWriter.class,
        NotificationCleanupJobExecutionListener.class
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationCleanupJobIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    private static final Instant CUTOFF = Instant.parse("2026-08-10T00:00:00Z");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String baseUrl = System.getenv().getOrDefault(
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://localhost:5432/testdb"
        );
        String separator = baseUrl.contains("?") ? "&" : "?";

        registry.add("spring.datasource.url", () -> baseUrl + separator + "currentSchema=notification_batch_test");
        registry.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "testuser")
        );
        registry.add(
                "spring.datasource.password",
                () -> System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "testpass")
        );
    }

    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final Job notificationCleanupJob;

    @MockitoSpyBean
    private NotificationCleanupItemWriter itemWriter;

    @Autowired
    NotificationCleanupJobIntegrationTest(
            JdbcTemplate jdbcTemplate,
            JobLauncher jobLauncher,
            Job notificationCleanupJob
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobLauncher = jobLauncher;
        this.notificationCleanupJob = notificationCleanupJob;
    }

    @AfterAll
    void dropTestSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS notification_batch_test CASCADE");
    }

    @Test
    void job_deletesOnlyNotificationsBeforeCutoffAcrossMultipleChunks() throws Exception {
        assertThat(indexCount("idx_notification_cleanup_created_at_id")).isOne();
        insertNotifications(CUTOFF.minusSeconds(1), 1005);
        insertNotifications(CUTOFF, 1);
        insertNotifications(CUTOFF.plusSeconds(1), 1);

        JobExecution execution = jobLauncher.run(
                notificationCleanupJob,
                jobParameters("2026-08-17-multi-chunk")
        );

        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getReadCount()).isEqualTo(1005);
        assertThat(stepExecution.getWriteCount()).isEqualTo(1005);
        assertThat(countNotifications()).isEqualTo(2);
    }

    @Test
    void job_restartsFromLastCommittedCheckpoint() throws Exception {
        jdbcTemplate.update("DELETE FROM notification");
        insertNotifications(CUTOFF.minusSeconds(1), 1005);

        AtomicInteger writeAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (writeAttempts.incrementAndGet() == 2) {
                throw new IllegalStateException("forced second chunk failure");
            }
            return invocation.callRealMethod();
        }).when(itemWriter).write(any());

        JobParameters parameters = jobParameters("2026-08-17-restart");
        JobExecution failedExecution = jobLauncher.run(notificationCleanupJob, parameters);

        assertThat(failedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution failedStep = failedExecution.getStepExecutions().iterator().next();
        assertThat(failedStep.getRollbackCount()).isPositive();
        assertThat(failedStep.getExecutionContext().containsKey("notificationCleanupReader.start.after"))
                .isTrue();
        assertThat(countNotifications()).isEqualTo(5);

        doCallRealMethod().when(itemWriter).write(any());
        JobExecution restartedExecution = jobLauncher.run(notificationCleanupJob, parameters);

        StepExecution restartedStep = restartedExecution.getStepExecutions().iterator().next();
        assertThat(restartedExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restartedExecution.getJobInstance().getInstanceId())
                .isEqualTo(failedExecution.getJobInstance().getInstanceId());
        assertThat(restartedExecution.getId()).isNotEqualTo(failedExecution.getId());
        assertThat(restartedStep.getId()).isNotEqualTo(failedStep.getId());
        assertThat(restartedStep.getReadCount()).isEqualTo(5);
        assertThat(restartedStep.getWriteCount()).isEqualTo(5);
        assertThat(countNotifications()).isZero();
    }

    @Test
    void job_rejectsDuplicateExecutionAfterJobInstanceCompleted() throws Exception {
        jdbcTemplate.update("DELETE FROM notification");
        insertNotifications(CUTOFF.minusSeconds(1), 1);
        JobParameters parameters = jobParameters("2026-08-17-duplicate");

        JobExecution completedExecution = jobLauncher.run(notificationCleanupJob, parameters);

        assertThat(completedExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThatThrownBy(() -> jobLauncher.run(notificationCleanupJob, parameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void job_rejectsConcurrentExecutionForSameJobInstance() throws Exception {
        jdbcTemplate.update("DELETE FROM notification");
        insertNotifications(CUTOFF.minusSeconds(1), 1);

        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        doAnswer(invocation -> {
            writerStarted.countDown();
            if (!releaseWriter.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("writer release timeout");
            }
            return invocation.callRealMethod();
        }).when(itemWriter).write(any());

        JobParameters parameters = jobParameters("2026-08-17-concurrent");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<JobExecution> firstExecution = executor.submit(
                () -> jobLauncher.run(notificationCleanupJob, parameters)
        );

        try {
            assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> jobLauncher.run(notificationCleanupJob, parameters))
                    .isInstanceOf(JobExecutionAlreadyRunningException.class);
        } finally {
            releaseWriter.countDown();
            executor.shutdown();
        }

        assertThat(firstExecution.get(5, TimeUnit.SECONDS).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private JobParameters jobParameters(String scheduledDate) {
        return new JobParametersBuilder()
                .addString(NotificationCleanupJobConfig.SCHEDULED_DATE_PARAMETER, scheduledDate, true)
                .addString(NotificationCleanupJobConfig.CUTOFF_PARAMETER, CUTOFF.toString(), true)
                .toJobParameters();
    }

    private void insertNotifications(Instant createdAt, int count) {
        List<Object[]> arguments = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new Object[]{Timestamp.from(createdAt)})
                .toList();
        jdbcTemplate.batchUpdate(
                "INSERT INTO notification (created_at) VALUES (?)",
                arguments
        );
    }

    private int countNotifications() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        return count == null ? 0 : count;
    }

    private int indexCount(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                Integer.class,
                indexName
        );
        return count == null ? 0 : count;
    }
}
