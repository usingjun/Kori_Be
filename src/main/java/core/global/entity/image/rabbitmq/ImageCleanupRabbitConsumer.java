package core.global.entity.image.rabbitmq;

import core.global.entity.image.service.FailedImageCleanupService;
import core.global.entity.image.service.ImageCleanupOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "image.cleanup.rabbit.enabled", havingValue = "true")
public class ImageCleanupRabbitConsumer {

    private final FailedImageCleanupService failedImageCleanupService;
    private final ImageCleanupOperationService operationService;
    private final ImageCleanupRabbitPublisher publisher;

    @RabbitListener(queues = ImageCleanupRabbitNames.QUEUE)
    public void consume(ImageCleanupMessage message) {
        if (!operationService.begin(message.operationId(), message.messageId())) {
            return;
        }

        try {
            failedImageCleanupService.executeCleanup(message.operationType(), message.targetKey());
            operationService.markCompleted(message.operationId(), message.messageId());
        } catch (Exception e) {
            ImageCleanupOperationService.FailureDecision decision =
                    operationService.markFailed(message.operationId(), e.getMessage());
            ImageCleanupMessage nextMessage = message.nextAttempt(decision.attempt());

            if (decision.exhausted()) {
                publisher.publishDlq(nextMessage);
                log.error("[ImageCleanupRabbit] moved to DLQ operationId={} type={} target={} attempt={}",
                        message.operationId(), message.operationType(), message.targetKey(), decision.attempt());
                return;
            }

            publisher.publishRetry(nextMessage);
            log.warn("[ImageCleanupRabbit] retry scheduled operationId={} type={} target={} attempt={}",
                    message.operationId(), message.operationType(), message.targetKey(), decision.attempt());
        }
    }
}
