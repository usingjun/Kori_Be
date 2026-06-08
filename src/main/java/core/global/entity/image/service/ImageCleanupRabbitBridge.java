package core.global.entity.image.service;

import core.global.entity.image.rabbitmq.ImageCleanupMessage;
import core.global.entity.image.rabbitmq.ImageCleanupRabbitPublisher;
import core.global.enums.common.ImageCleanupOperationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCleanupRabbitBridge {

    private final ImageCleanupOperationService operationService;
    private final ImageCleanupRabbitPublisher publisher;

    public void recordAndPublish(
            ImageCleanupOperationType operationType,
            String targetKey,
            String errorMessage
    ) {
        try {
            ImageCleanupMessage message = operationService.registerFailure(operationType, targetKey, errorMessage);
            publisher.publishInitial(message);
        } catch (Exception e) {
            log.warn("[ImageCleanupRabbit] operation tracking or publish failed; fallback remains active. type={} target={} err={}",
                    operationType, targetKey, e.getMessage());
        }
    }
}
