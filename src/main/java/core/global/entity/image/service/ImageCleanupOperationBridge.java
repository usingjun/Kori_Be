package core.global.entity.image.service;

import core.global.enums.common.ImageCleanupOperationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCleanupOperationBridge {

    private final ImageOperationRecoveryService recoveryService;

    public void recordCommonOutbox(
            Long failedCleanupId,
            ImageCleanupOperationType operationType,
            String targetKey
    ) {
        try {
            recoveryService.scheduleFailedCleanup(failedCleanupId, operationType, targetKey);
        } catch (Exception e) {
            log.warn("[ImageCleanupRabbit] common operation outbox failed; fallback remains active. type={} target={} err={}",
                    operationType, targetKey, e.getMessage());
        }
    }
}
