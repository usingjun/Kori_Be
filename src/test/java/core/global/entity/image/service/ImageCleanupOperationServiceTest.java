package core.global.entity.image.service;

import core.global.entity.image.entity.ImageCleanupConsumedMessage;
import core.global.entity.image.entity.ImageCleanupOperation;
import core.global.entity.image.repository.ImageCleanupAuditLogRepository;
import core.global.entity.image.repository.ImageCleanupConsumedMessageRepository;
import core.global.entity.image.repository.ImageCleanupOperationRepository;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupRabbitStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageCleanupOperationServiceTest {

    @Mock
    private ImageCleanupOperationRepository operationRepository;
    @Mock
    private ImageCleanupAuditLogRepository auditRepository;
    @Mock
    private ImageCleanupConsumedMessageRepository consumedMessageRepository;

    @InjectMocks
    private ImageCleanupOperationService operationService;

    @Test
    @DisplayName("consumer 시작 시 messageId를 선점하고 PROCESSING으로 전환한다")
    void begin_claimsMessageIdAndMarksProcessing() {
        ImageCleanupOperation operation = operation();
        UUID messageId = UUID.randomUUID();
        when(consumedMessageRepository.existsById(messageId)).thenReturn(false);
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));

        boolean started = operationService.begin(operation.getOperationId(), messageId);

        assertThat(started).isTrue();
        assertThat(operation.getStatus()).isEqualTo(ImageCleanupRabbitStatus.PROCESSING);
        ArgumentCaptor<ImageCleanupConsumedMessage> captor =
                ArgumentCaptor.forClass(ImageCleanupConsumedMessage.class);
        verify(consumedMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(messageId);
        assertThat(captor.getValue().getOperationId()).isEqualTo(operation.getOperationId());
    }

    @Test
    @DisplayName("이미 소비한 messageId는 operation을 시작하지 않는다")
    void begin_skipsConsumedMessageId() {
        UUID operationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(consumedMessageRepository.existsById(messageId)).thenReturn(true);

        boolean started = operationService.begin(operationId, messageId);

        assertThat(started).isFalse();
        verify(operationRepository, never()).findById(any());
        verify(consumedMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("재시도 한도에 도달하면 operation을 DLQ로 전환한다")
    void markFailed_movesOperationToDlqAtMaxAttempts() {
        ImageCleanupOperation operation = operation();
        operation.markRetryWaiting("1");
        operation.markRetryWaiting("2");
        operation.markRetryWaiting("3");
        operation.markRetryWaiting("4");
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));

        ImageCleanupOperationService.FailureDecision decision =
                operationService.markFailed(operation.getOperationId(), "last failure");

        assertThat(decision.exhausted()).isTrue();
        assertThat(decision.attempt()).isEqualTo(5);
        assertThat(operation.getStatus()).isEqualTo(ImageCleanupRabbitStatus.DLQ);
    }

    private ImageCleanupOperation operation() {
        return ImageCleanupOperation.create(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg",
                "timeout",
                5
        );
    }
}
