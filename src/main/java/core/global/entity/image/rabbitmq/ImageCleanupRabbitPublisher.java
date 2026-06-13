package core.global.entity.image.rabbitmq;

import core.global.enums.common.ImageCleanupOperationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static core.global.entity.image.rabbitmq.ImageCleanupRabbitNames.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${image.cleanup.rabbit.enabled:false}")
    private boolean enabled;

    public void publishInitial(ImageCleanupMessage message) {
        if (!enabled) return;
        rabbitTemplate.convertAndSend(EXCHANGE, cleanupRoutingKey(message.operationType()), message);
    }

    public void publishRetry(ImageCleanupMessage message) {
        if (!enabled) return;
        rabbitTemplate.convertAndSend(RETRY_EXCHANGE, retryRoutingKey(message.attempt()), message);
    }

    public void publishDlq(ImageCleanupMessage message) {
        if (!enabled) return;
        rabbitTemplate.convertAndSend(DLX, DLQ_ROUTING_KEY, message);
    }

    private String cleanupRoutingKey(ImageCleanupOperationType operationType) {
        return switch (operationType) {
            case DELETE_OBJECT -> DELETE_OBJECT_ROUTING_KEY;
            case DELETE_FOLDER -> DELETE_FOLDER_ROUTING_KEY;
        };
    }

    private String retryRoutingKey(int attempt) {
        if (attempt <= 1) return "retry.1m";
        if (attempt == 2) return "retry.5m";
        if (attempt == 3) return "retry.15m";
        return "retry.1h";
    }
}
