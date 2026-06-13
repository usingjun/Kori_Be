package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = {
                "image.cleanup.rabbit.enabled",
                "image.unregistered-post-object-cleanup.enabled"
        },
        havingValue = "true"
)
public class UnregisteredPostImageCleanupScheduler {

    private final ImageUploadSessionService uploadSessionService;

    @Scheduled(
            fixedDelayString = "${image.unregistered-post-object-cleanup.fixed-delay:PT1H}",
            initialDelayString = "${image.unregistered-post-object-cleanup.initial-delay:PT5M}"
    )
    public void scheduleCleanup() {
        int scheduled = uploadSessionService.scheduleExpiredSessions();
        if (scheduled > 0) {
            log.info("[UnregisteredPostImageCleanup] scheduled={}", scheduled);
        }
    }

    @Scheduled(
            fixedDelayString = "${image.upload-session.delete-failed-retry-delay-ms:3600000}",
            initialDelayString = "${image.upload-session.delete-failed-retry-initial-delay-ms:300000}"
    )
    public void retryFailedDeletes() {
        int scheduled = uploadSessionService.retryFailedDeletes();
        if (scheduled > 0) {
            log.info("[UnregisteredPostImageCleanup] failed delete retry scheduled={}", scheduled);
        }
    }

}
