package core.global.entity.image.rabbitmq;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.enums.common.ImageOperationMessageDestination;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static core.global.entity.image.rabbitmq.ImageCleanupRabbitNames.*;
import static core.global.entity.image.rabbitmq.ImageOperationRabbitNames.*;

@Component
@RequiredArgsConstructor
public class ImageOperationRabbitPublisher {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;

    public void publishWithConfirm(ImageOperationPublishOutbox outbox) {
        ImageOperationMessage message = ImageOperationMessage.from(outbox);
        Destination destination = destination(outbox);

        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(destination.exchange(), destination.routingKey(), message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
            return null;
        });
    }

    private Destination destination(ImageOperationPublishOutbox outbox) {
        if (outbox.getDestination() == ImageOperationMessageDestination.DLQ) {
            return new Destination(DLX, STEP_DLQ_ROUTING_KEY);
        }
        if (outbox.getDestination() == ImageOperationMessageDestination.RETRY) {
            return new Destination(RETRY_EXCHANGE, retryRoutingKey(outbox.getAttempt()));
        }
        return new Destination(EXCHANGE, COMPENSATE_ROUTING_KEY);
    }

    private String retryRoutingKey(int attempt) {
        if (attempt <= 1) return STEP_RETRY_PREFIX + "1m";
        if (attempt == 2) return STEP_RETRY_PREFIX + "5m";
        if (attempt == 3) return STEP_RETRY_PREFIX + "15m";
        return STEP_RETRY_PREFIX + "1h";
    }

    private record Destination(String exchange, String routingKey) {
    }
}
