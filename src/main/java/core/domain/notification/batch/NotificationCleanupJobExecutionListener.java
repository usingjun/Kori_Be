package core.domain.notification.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationCleanupJobExecutionListener implements JobExecutionListener {

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info(
                "[BATCH] 오래된 알림 삭제 Job 시작. jobExecutionId={}, parameters={}",
                jobExecution.getId(),
                jobExecution.getJobParameters()
        );
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        long totalProcessed = jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getWriteCount)
                .sum();

        log.info(
                "[BATCH] 오래된 알림 삭제 Job 종료. jobExecutionId={}, status={}, totalProcessed={}",
                jobExecution.getId(),
                jobExecution.getStatus(),
                totalProcessed
        );
    }
}
