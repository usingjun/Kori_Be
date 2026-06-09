package core.global.entity.image.rabbitmq;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.enums.common.ImageOperationMessageDestination;
import core.global.enums.common.ImageOperationStepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;

import static core.global.entity.image.rabbitmq.ImageCleanupRabbitNames.RETRY_EXCHANGE;
import static core.global.entity.image.rabbitmq.ImageOperationRabbitNames.STEP_RETRY_PREFIX;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOperationRabbitPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private RabbitOperations rabbitOperations;

    @InjectMocks
    private ImageOperationRabbitPublisher publisher;

    @Test
    @SuppressWarnings("unchecked")
    void publishWithConfirm_waitsForBrokerConfirm() {
        ImageOperationPublishOutbox outbox = ImageOperationPublishOutbox.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                "users/10/profile.jpg",
                2,
                ImageOperationMessageDestination.RETRY
        );
        when(rabbitTemplate.invoke(any(RabbitOperations.OperationsCallback.class)))
                .thenAnswer(invocation -> {
                    RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
                    return callback.doInRabbit(rabbitOperations);
                });

        publisher.publishWithConfirm(outbox);

        verify(rabbitOperations).convertAndSend(
                eq(RETRY_EXCHANGE),
                eq(STEP_RETRY_PREFIX + "5m"),
                any(ImageOperationMessage.class)
        );
        verify(rabbitOperations).waitForConfirmsOrDie(5000);
    }
}
