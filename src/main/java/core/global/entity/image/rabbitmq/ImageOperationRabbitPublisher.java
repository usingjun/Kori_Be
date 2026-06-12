package core.global.entity.image.rabbitmq;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.enums.common.ImageOperationMessageDestination;
import core.global.enums.common.ImageOperationStepType;
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
        return new Destination(EXCHANGE, initialRoutingKey(outbox.getStepType()));
    }

    private String initialRoutingKey(ImageOperationStepType stepType) {
        return switch (stepType) {
            case COMPENSATE_FINAL_OBJECT -> COMPENSATE_ROUTING_KEY;
            case COPY_STAGING_TO_FINAL -> COPY_ROUTING_KEY;
            case REGISTER_IMAGE_DB -> REGISTER_IMAGE_DB_ROUTING_KEY;
            case DELETE_STAGING -> DELETE_STAGING_ROUTING_KEY;
            case DELETE_OBJECT -> ImageOperationRabbitNames.DELETE_OBJECT_ROUTING_KEY;
            case DELETE_FOLDER -> ImageOperationRabbitNames.DELETE_FOLDER_ROUTING_KEY;
            default -> throw new IllegalArgumentException("Unsupported initial image operation step type " + stepType);
        };
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
