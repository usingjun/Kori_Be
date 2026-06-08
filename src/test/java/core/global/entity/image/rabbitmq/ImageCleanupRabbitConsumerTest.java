package core.global.entity.image.rabbitmq;

import core.global.entity.image.service.FailedImageCleanupService;
import core.global.entity.image.service.ImageCleanupOperationService;
import core.global.enums.common.ImageCleanupOperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageCleanupRabbitConsumerTest {

    @Mock
    private FailedImageCleanupService failedImageCleanupService;
    @Mock
    private ImageCleanupOperationService operationService;
    @Mock
    private ImageCleanupRabbitPublisher publisher;

    @InjectMocks
    private ImageCleanupRabbitConsumer consumer;

    @Test
    @DisplayName("cleanup 성공 시 operation을 완료한다")
    void consume_marksCompletedWhenCleanupSucceeds() {
        ImageCleanupMessage message = message(0);
        when(operationService.begin(message.operationId(), message.messageId())).thenReturn(true);

        consumer.consume(message);

        verify(failedImageCleanupService).executeCleanup(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );
        verify(operationService).markCompleted(message.operationId(), message.messageId());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("이미 소비한 messageId는 S3 cleanup을 다시 실행하지 않는다")
    void consume_skipsAlreadyConsumedMessage() {
        ImageCleanupMessage message = message(0);
        when(operationService.begin(message.operationId(), message.messageId())).thenReturn(false);

        consumer.consume(message);

        verifyNoInteractions(failedImageCleanupService, publisher);
        verify(operationService, never()).markCompleted(any(), any());
        verify(operationService, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("cleanup 실패 시 다음 retry queue로 새 messageId를 발행한다")
    void consume_publishesRetryWhenAttemptsRemain() {
        ImageCleanupMessage message = message(0);
        when(operationService.begin(message.operationId(), message.messageId())).thenReturn(true);
        doThrow(new IllegalStateException("storage down"))
                .when(failedImageCleanupService)
                .executeCleanup(message.operationType(), message.targetKey());
        when(operationService.markFailed(message.operationId(), "storage down"))
                .thenReturn(new ImageCleanupOperationService.FailureDecision(false, 1));

        consumer.consume(message);

        ArgumentCaptor<ImageCleanupMessage> captor = ArgumentCaptor.forClass(ImageCleanupMessage.class);
        verify(publisher).publishRetry(captor.capture());
        assertThat(captor.getValue().operationId()).isEqualTo(message.operationId());
        assertThat(captor.getValue().messageId()).isNotEqualTo(message.messageId());
        assertThat(captor.getValue().attempt()).isEqualTo(1);
        verify(publisher, never()).publishDlq(any());
    }

    @Test
    @DisplayName("cleanup 재시도 한도 초과 시 DLQ로 발행한다")
    void consume_publishesDlqWhenAttemptsExhausted() {
        ImageCleanupMessage message = message(4);
        when(operationService.begin(message.operationId(), message.messageId())).thenReturn(true);
        doThrow(new IllegalStateException("storage down"))
                .when(failedImageCleanupService)
                .executeCleanup(message.operationType(), message.targetKey());
        when(operationService.markFailed(message.operationId(), "storage down"))
                .thenReturn(new ImageCleanupOperationService.FailureDecision(true, 5));

        consumer.consume(message);

        ArgumentCaptor<ImageCleanupMessage> captor = ArgumentCaptor.forClass(ImageCleanupMessage.class);
        verify(publisher).publishDlq(captor.capture());
        assertThat(captor.getValue().attempt()).isEqualTo(5);
        verify(publisher, never()).publishRetry(any());
    }

    private ImageCleanupMessage message(int attempt) {
        return new ImageCleanupMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg",
                attempt,
                1,
                Instant.now()
        );
    }
}
