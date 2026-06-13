package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "image.cleanup.rabbit.enabled", havingValue = "true")
public class ImageOperationTimeoutRecoveryScheduler {

    private final ImageOperationRecoveryService recoveryService;

    @Value("${image.operation.processing-timeout:PT5M}")
    private Duration processingTimeout;

    @Scheduled(fixedDelayString = "${image.operation.timeout-recovery-delay-ms:60000}")
    public void recoverTimedOutDeleteSteps() {
        LocalDateTime timedOutBefore = LocalDateTime.now().minus(processingTimeout);
        int recoveredDeletes = recoveryService.recoverTimedOutDeleteSteps(timedOutBefore);
        int recoveredPipelineSteps = recoveryService.recoverTimedOutPipelineSteps(timedOutBefore);
        if (recoveredDeletes > 0 || recoveredPipelineSteps > 0) {
            log.warn("[ImageOperationRecovery] recovered timed-out deleteSteps={} pipelineSteps={}",
                    recoveredDeletes, recoveredPipelineSteps);
        }
    }
}
