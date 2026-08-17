package core.domain.notification.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
public class NotificationCleanupScheduler {

    private static final LocalTime SCHEDULED_TIME = LocalTime.of(4, 0);
    private static final long RETENTION_DAYS = 7L;

    private final JobLauncher jobLauncher;
    private final Job notificationCleanupJob;
    private final Clock notificationCleanupClock;

    public NotificationCleanupScheduler(
            JobLauncher jobLauncher,
            @Qualifier(NotificationCleanupJobConfig.JOB_NAME) Job notificationCleanupJob,
            @Qualifier("notificationCleanupClock") Clock notificationCleanupClock
    ) {
        this.jobLauncher = jobLauncher;
        this.notificationCleanupJob = notificationCleanupJob;
        this.notificationCleanupClock = notificationCleanupClock;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledDeleteOldNotifications() {
        JobParameters jobParameters = createJobParameters();

        try {
            JobExecution jobExecution = jobLauncher.run(notificationCleanupJob, jobParameters);
            log.info(
                    "[BATCH] 오래된 알림 삭제 Job 실행 요청 완료. jobExecutionId={}, status={}",
                    jobExecution.getId(),
                    jobExecution.getStatus()
            );
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException e) {
            log.info("[BATCH] 동일한 오래된 알림 삭제 Job이 이미 실행 중이거나 완료되었습니다.");
        } catch (Exception e) {
            log.error("[BATCH] 오래된 알림 삭제 Job 실행 요청 중 오류 발생", e);
        }
    }

    JobParameters createJobParameters() {
        LocalDate scheduledDate = LocalDate.now(notificationCleanupClock);
        ZonedDateTime scheduledAt = scheduledDate
                .atTime(SCHEDULED_TIME)
                .atZone(notificationCleanupClock.getZone());
        Instant cutoff = scheduledAt.toInstant().minus(RETENTION_DAYS, ChronoUnit.DAYS);

        return new JobParametersBuilder()
                .addString(
                        NotificationCleanupJobConfig.SCHEDULED_DATE_PARAMETER,
                        scheduledDate.toString(),
                        true
                )
                .addString(
                        NotificationCleanupJobConfig.CUTOFF_PARAMETER,
                        cutoff.toString(),
                        true
                )
                .toJobParameters();
    }
}
