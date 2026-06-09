package core.global.entity.image.rabbitmq;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.entity.image.service.ImageOperationOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "image.cleanup.rabbit.enabled", havingValue = "true")
public class ImageOperationOutboxRelay {

    private final ImageOperationOutboxService outboxService;
    private final ImageOperationRabbitPublisher publisher;

    @Scheduled(fixedDelayString = "${image.operation.outbox.fixed-delay-ms:1000}")
    public void publishPending() {
        for (ImageOperationPublishOutbox outbox : outboxService.findPendingBatch()) {
            try {
                publisher.publishWithConfirm(outbox);
                outboxService.markPublished(outbox.getOutboxId());
            } catch (RuntimeException e) {
                outboxService.recordFailure(outbox.getOutboxId(), e.getMessage());
                log.warn("[ImageOperationOutbox] publish failed outboxId={} operationId={}",
                        outbox.getOutboxId(), outbox.getOperationId(), e);
            }
        }
    }
}
