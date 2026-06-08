package core.global.entity.image.rabbitmq;

import core.global.enums.common.ImageCleanupOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static core.global.entity.image.rabbitmq.ImageCleanupRabbitNames.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ImageCleanupRabbitPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private ImageCleanupRabbitPublisher publisher;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(publisher, "enabled", true);
    }

    @Test
    @DisplayName("DELETE_OBJECT 초기 message를 cleanup routing key로 발행한다")
    void publishInitial_routesDeleteObjectMessage() {
        ImageCleanupMessage message = ImageCleanupMessage.initial(
                java.util.UUID.randomUUID(),
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );

        publisher.publishInitial(message);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, DELETE_OBJECT_ROUTING_KEY, message);
    }

    @Test
    @DisplayName("attempt에 맞는 retry queue routing key로 발행한다")
    void publishRetry_routesByAttempt() {
        ImageCleanupMessage message = ImageCleanupMessage.initial(
                java.util.UUID.randomUUID(),
                ImageCleanupOperationType.DELETE_FOLDER,
                "posts/1/"
        ).nextAttempt(3);

        publisher.publishRetry(message);

        verify(rabbitTemplate).convertAndSend(RETRY_EXCHANGE, "retry.15m", message);
    }

    @Test
    @DisplayName("RabbitMQ 비활성화 시 message를 발행하지 않는다")
    void publishInitial_doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(publisher, "enabled", false);
        ImageCleanupMessage message = ImageCleanupMessage.initial(
                java.util.UUID.randomUUID(),
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );

        publisher.publishInitial(message);

        verifyNoInteractions(rabbitTemplate);
    }
}
