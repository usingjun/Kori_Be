package core.global.entity.image.rabbitmq;

import core.global.entity.image.service.ImageCompensationExecutor;
import core.global.entity.image.service.ImageOperationRecoveryService;
import core.global.enums.common.ImageOperationStepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOperationRabbitConsumerTest {

    @Mock
    private ImageOperationRecoveryService recoveryService;
    @Mock
    private ImageCompensationExecutor compensationExecutor;

    @InjectMocks
    private ImageOperationRabbitConsumer consumer;

    @Test
    void consume_deletesCompensationTargetAndCompletesStep() {
        ImageOperationMessage message = message();
        when(recoveryService.begin(any())).thenReturn(true);

        consumer.consume(message);

        verify(compensationExecutor).deleteFinalObject(message.targetKey());
        verify(recoveryService).markCompleted(any());
        verify(recoveryService, never()).markFailedAndSchedule(any(), any());
    }

    @Test
    void consume_schedulesRetryWhenCompensationFails() {
        ImageOperationMessage message = message();
        when(recoveryService.begin(any())).thenReturn(true);
        doThrow(new IllegalStateException("delete failed"))
                .when(compensationExecutor).deleteFinalObject(message.targetKey());
        when(recoveryService.markFailedAndSchedule(any(), eq("delete failed")))
                .thenReturn(new ImageOperationRecoveryService.FailureDecision(false, 1));

        consumer.consume(message);

        verify(recoveryService).markFailedAndSchedule(any(), eq("delete failed"));
        verify(recoveryService, never()).markCompleted(any());
    }

    @Test
    void consume_skipsDuplicateMessage() {
        ImageOperationMessage message = message();
        when(recoveryService.begin(any())).thenReturn(false);

        consumer.consume(message);

        verifyNoInteractions(compensationExecutor);
        verify(recoveryService, never()).markCompleted(any());
    }

    private ImageOperationMessage message() {
        return new ImageOperationMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                "users/10/profile.jpg",
                0,
                1,
                Instant.now()
        );
    }
}
