package core.domain.notification.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationCleanupSchedulerTest {

    private JobLauncher jobLauncher;
    private Job job;
    private NotificationCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        jobLauncher = mock(JobLauncher.class);
        job = mock(Job.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-17T04:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        scheduler = new NotificationCleanupScheduler(jobLauncher, job, clock);
    }

    @Test
    void createJobParameters_usesDeterministicScheduledDateAndCutoff() {
        JobParameters parameters = scheduler.createJobParameters();

        assertThat(parameters.getString(NotificationCleanupJobConfig.SCHEDULED_DATE_PARAMETER))
                .isEqualTo("2026-08-17");
        assertThat(parameters.getString(NotificationCleanupJobConfig.CUTOFF_PARAMETER))
                .isEqualTo("2026-08-09T19:00:00Z");
        assertThat(parameters.getParameters().values())
                .allMatch(JobParameter::isIdentifying);
    }

    @Test
    void createJobParameters_sameDateProducesSameJobInstanceParameters() {
        assertThat(scheduler.createJobParameters())
                .isEqualTo(scheduler.createJobParameters());
    }

    @Test
    void createJobParameters_preservesExactSevenDayRetentionAcrossDst() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Clock dstClock = Clock.fixed(Instant.parse("2026-03-10T12:00:00Z"), newYork);
        NotificationCleanupScheduler dstScheduler = new NotificationCleanupScheduler(jobLauncher, job, dstClock);

        JobParameters parameters = dstScheduler.createJobParameters();
        Instant scheduledAt = LocalDate.of(2026, 3, 10)
                .atTime(LocalTime.of(4, 0))
                .atZone(newYork)
                .toInstant();
        Instant cutoff = Instant.parse(parameters.getString(NotificationCleanupJobConfig.CUTOFF_PARAMETER));

        assertThat(Duration.between(cutoff, scheduledAt)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void scheduledDeleteOldNotifications_launchesJobWithParameters() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getId()).thenReturn(10L);
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);

        scheduler.scheduledDeleteOldNotifications();

        verify(jobLauncher).run(job, scheduler.createJobParameters());
    }

    @Test
    void scheduledDeleteOldNotifications_ignoresAlreadyRunningInstance() throws Exception {
        when(jobLauncher.run(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobExecutionAlreadyRunningException("already running"));

        assertThatCode(scheduler::scheduledDeleteOldNotifications)
                .doesNotThrowAnyException();
    }

    @Test
    void scheduledDeleteOldNotifications_keepsFourAmCron() throws Exception {
        Scheduled scheduled = NotificationCleanupScheduler.class
                .getMethod("scheduledDeleteOldNotifications")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
        assertThat(scheduled.zone()).isEmpty();
    }
}
