package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailedImageCleanupScheduler {

    private final FailedImageCleanupService failedImageCleanupService;

    @Scheduled(
            fixedDelayString = "${image.cleanup.retry.fixed-delay:PT5M}",
            initialDelayString = "${image.cleanup.retry.initial-delay:PT1M}"
    )
    public void retryFailedCleanups() {
        int processed = failedImageCleanupService.retryDueCleanups();
        if (processed > 0) {
            log.info("[ImageCleanup] retried {} cleanup jobs", processed);
        }
    }
}
