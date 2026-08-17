package core.domain.notification.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class NotificationCleanupJobConfig {

    public static final String JOB_NAME = "notificationCleanupJob";
    public static final String STEP_NAME = "notificationCleanupStep";
    public static final String CUTOFF_PARAMETER = "cutoff";
    public static final String SCHEDULED_DATE_PARAMETER = "scheduledDate";
    static final int CHUNK_SIZE = 1000;

    @Bean(name = "notificationCleanupClock")
    public Clock notificationCleanupClock() {
        return Clock.systemDefaultZone();
    }

    @Bean(name = JOB_NAME)
    public Job notificationCleanupJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step notificationCleanupStep,
            NotificationCleanupJobExecutionListener listener
    ) {
        DefaultJobParametersValidator validator = new DefaultJobParametersValidator(
                new String[]{SCHEDULED_DATE_PARAMETER, CUTOFF_PARAMETER},
                new String[0]
        );

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .listener(listener)
                .start(notificationCleanupStep)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step notificationCleanupStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<Long> notificationCleanupReader,
            NotificationCleanupItemWriter notificationCleanupItemWriter
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Long, Long>chunk(CHUNK_SIZE, transactionManager)
                .reader(notificationCleanupReader)
                .writer(notificationCleanupItemWriter)
                .build();
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> notificationCleanupReader(
            DataSource dataSource,
            @Value("#{jobParameters['" + CUTOFF_PARAMETER + "']}") String cutoff
    ) {
        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put("created_at", Order.ASCENDING);
        sortKeys.put("notification_id", Order.ASCENDING);

        return new JdbcPagingItemReaderBuilder<Long>()
                .name("notificationCleanupReader")
                .dataSource(dataSource)
                .selectClause("SELECT notification_id, created_at")
                .fromClause("FROM notification")
                .whereClause("WHERE created_at < :cutoff")
                .sortKeys(sortKeys)
                .parameterValues(Map.of(
                        CUTOFF_PARAMETER,
                        Timestamp.from(Instant.parse(cutoff))
                ))
                .rowMapper((resultSet, rowNum) -> resultSet.getLong("notification_id"))
                .pageSize(CHUNK_SIZE)
                .fetchSize(CHUNK_SIZE)
                .saveState(true)
                .build();
    }
}
