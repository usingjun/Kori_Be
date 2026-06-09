package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationConsumedMessageRepository;
import core.global.entity.image.repository.ImageOperationPublishOutboxRepository;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOperationRecoveryServiceTest {

    @Mock
    private ImageOperationRepository operationRepository;
    @Mock
    private ImageOperationStepRepository stepRepository;
    @Mock
    private ImageOperationPublishOutboxRepository outboxRepository;
    @Mock
    private ImageOperationConsumedMessageRepository consumedMessageRepository;

    @InjectMocks
    private ImageOperationRecoveryService recoveryService;

    private ImageOperation operation;
    private ImageOperationStep compensationStep;

    @BeforeEach
    void setUp() {
        operation = ImageOperation.create(
                ImageOperationType.UPDATE_USER_PROFILE_IMAGE,
                ImageOperationOwnerType.USER,
                10L
        );
        operation.markProcessing();
        compensationStep = ImageOperationStep.createCompensationStep(
                operation.getOperationId(),
                "users/10/profile.jpg",
                5
        );
    }

    @Test
    @DisplayName("보상 step과 초기 Outbox를 함께 생성하고 operation을 FAILED 처리한다")
    void scheduleCompensation_createsStepAndOutbox() {
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                operation.getOperationId(),
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                compensationStep.getTargetKey()
        )).thenReturn(Optional.empty());
        when(stepRepository.save(any())).thenReturn(compensationStep);

        recoveryService.scheduleCompensation(operation.getOperationId(), compensationStep.getTargetKey());

        ArgumentCaptor<ImageOperationPublishOutbox> captor =
                ArgumentCaptor.forClass(ImageOperationPublishOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getDestination()).isEqualTo(ImageOperationMessageDestination.INITIAL);
        assertThat(captor.getValue().getStepId()).isEqualTo(compensationStep.getStepId());
        assertThat(operation.getStatus()).isEqualTo(ImageOperationStatus.FAILED);
    }

    @Test
    @DisplayName("보상 실패 시 step을 RETRY_WAITING으로 바꾸고 retry Outbox를 생성한다")
    void markFailedAndSchedule_createsRetryOutbox() {
        compensationStep.markProcessing();
        ImageOperationRecoveryService.ImageOperationMessageView message = messageView(compensationStep, 0);
        when(stepRepository.findById(compensationStep.getStepId())).thenReturn(Optional.of(compensationStep));

        ImageOperationRecoveryService.FailureDecision decision =
                recoveryService.markFailedAndSchedule(message, "storage unavailable");

        ArgumentCaptor<ImageOperationPublishOutbox> captor =
                ArgumentCaptor.forClass(ImageOperationPublishOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(decision.exhausted()).isFalse();
        assertThat(decision.attempt()).isEqualTo(1);
        assertThat(compensationStep.getStatus()).isEqualTo(ImageOperationStepStatus.RETRY_WAITING);
        assertThat(captor.getValue().getDestination()).isEqualTo(ImageOperationMessageDestination.RETRY);
        verify(consumedMessageRepository).insertIfAbsent(
                message.messageId(), message.operationId(), message.stepId(), "image-operation-compensation-consumer"
        );
    }

    @Test
    @DisplayName("이미 소비된 message는 보상 작업을 시작하지 않는다")
    void begin_skipsConsumedMessage() {
        ImageOperationRecoveryService.ImageOperationMessageView message = messageView(compensationStep, 0);
        when(consumedMessageRepository.existsById(message.messageId())).thenReturn(true);

        assertThat(recoveryService.begin(message)).isFalse();
        verifyNoInteractions(stepRepository);
    }

    @Test
    @DisplayName("보상 삭제 완료 시 step과 operation을 각각 COMPLETED와 COMPENSATED 처리한다")
    void markCompleted_marksOperationCompensated() {
        operation.markFailed();
        compensationStep.markProcessing();
        ImageOperationRecoveryService.ImageOperationMessageView message = messageView(compensationStep, 0);
        when(stepRepository.findById(compensationStep.getStepId())).thenReturn(Optional.of(compensationStep));
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));

        recoveryService.markCompleted(message);

        assertThat(compensationStep.getStatus()).isEqualTo(ImageOperationStepStatus.COMPLETED);
        assertThat(operation.getStatus()).isEqualTo(ImageOperationStatus.COMPENSATED);
        verify(consumedMessageRepository).insertIfAbsent(
                message.messageId(), message.operationId(), message.stepId(), "image-operation-compensation-consumer"
        );
    }

    @Test
    @DisplayName("오래된 PROCESSING 보상 step을 RETRY_WAITING으로 복구하고 retry Outbox를 생성한다")
    void recoverTimedOutCompensations_schedulesRetry() {
        compensationStep.markProcessing();
        LocalDateTime timedOutBefore = LocalDateTime.now();
        when(stepRepository.findTop50ByStepTypeAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                ImageOperationStepStatus.PROCESSING,
                timedOutBefore
        )).thenReturn(List.of(compensationStep));

        int recovered = recoveryService.recoverTimedOutCompensations(timedOutBefore);

        ArgumentCaptor<ImageOperationPublishOutbox> captor =
                ArgumentCaptor.forClass(ImageOperationPublishOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(recovered).isEqualTo(1);
        assertThat(compensationStep.getStatus()).isEqualTo(ImageOperationStepStatus.RETRY_WAITING);
        assertThat(compensationStep.getAttemptCount()).isEqualTo(1);
        assertThat(captor.getValue().getDestination()).isEqualTo(ImageOperationMessageDestination.RETRY);
    }

    @Test
    @DisplayName("timeout 복구 중 재시도 한도를 소진하면 step과 operation을 DLQ 처리한다")
    void recoverTimedOutCompensations_movesExhaustedStepToDlq() {
        ImageOperationStep exhaustedStep = ImageOperationStep.createCompensationStep(
                operation.getOperationId(),
                "users/10/exhausted.jpg",
                1
        );
        exhaustedStep.markProcessing();
        LocalDateTime timedOutBefore = LocalDateTime.now();
        when(stepRepository.findTop50ByStepTypeAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                ImageOperationStepStatus.PROCESSING,
                timedOutBefore
        )).thenReturn(List.of(exhaustedStep));
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));

        recoveryService.recoverTimedOutCompensations(timedOutBefore);

        ArgumentCaptor<ImageOperationPublishOutbox> captor =
                ArgumentCaptor.forClass(ImageOperationPublishOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(exhaustedStep.getStatus()).isEqualTo(ImageOperationStepStatus.DLQ);
        assertThat(operation.getStatus()).isEqualTo(ImageOperationStatus.DLQ);
        assertThat(captor.getValue().getDestination()).isEqualTo(ImageOperationMessageDestination.DLQ);
    }

    @Test
    @DisplayName("timeout 복구 후 기존 삭제 호출이 늦게 성공해도 최종 보상 완료로 인정한다")
    void markCompleted_acceptsLateSuccessAfterTimeoutRecovery() {
        operation.markFailed();
        compensationStep.markProcessing();
        compensationStep.markFailed("Compensation processing timeout");
        ImageOperationRecoveryService.ImageOperationMessageView message = messageView(compensationStep, 0);
        when(stepRepository.findById(compensationStep.getStepId())).thenReturn(Optional.of(compensationStep));
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));

        recoveryService.markCompleted(message);

        assertThat(compensationStep.getStatus()).isEqualTo(ImageOperationStepStatus.COMPLETED);
        assertThat(operation.getStatus()).isEqualTo(ImageOperationStatus.COMPENSATED);
    }

    private ImageOperationRecoveryService.ImageOperationMessageView messageView(
            ImageOperationStep step,
            int attempt
    ) {
        return new ImageOperationRecoveryService.ImageOperationMessageView(
                UUID.randomUUID(),
                step.getOperationId(),
                step.getStepId(),
                step.getStepType(),
                step.getTargetKey(),
                attempt
        );
    }
}
