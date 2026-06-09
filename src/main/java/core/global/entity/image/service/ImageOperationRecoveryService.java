package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationConsumedMessageRepository;
import core.global.entity.image.repository.ImageOperationPublishOutboxRepository;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationMessageDestination;
import core.global.enums.common.ImageOperationStepStatus;
import core.global.enums.common.ImageOperationStepType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageOperationRecoveryService {

    private static final String CONSUMER_NAME = "image-operation-compensation-consumer";
    private static final int DEFAULT_COMPENSATION_MAX_ATTEMPTS = 5;

    private final ImageOperationRepository operationRepository;
    private final ImageOperationStepRepository stepRepository;
    private final ImageOperationPublishOutboxRepository outboxRepository;
    private final ImageOperationConsumedMessageRepository consumedMessageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleCompensation(UUID operationId, String targetKey) {
        ImageOperation operation = operationRepository.findById(operationId).orElseThrow();
        if (stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                operationId,
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                targetKey
        ).isPresent()) {
            return;
        }

        ImageOperationStep step = stepRepository.save(
                ImageOperationStep.createCompensationStep(
                        operationId,
                        targetKey,
                        DEFAULT_COMPENSATION_MAX_ATTEMPTS
                )
        );
        outboxRepository.save(outbox(step, 0, ImageOperationMessageDestination.INITIAL));
        operation.markFailed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean begin(ImageOperationMessageView message) {
        if (consumedMessageRepository.existsById(message.messageId())) {
            return false;
        }
        ImageOperationStep step = findMatchingStep(message);
        if (step.getStatus() == ImageOperationStepStatus.COMPLETED
                || step.getStatus() == ImageOperationStepStatus.DLQ) {
            return false;
        }
        if (step.getStatus() != ImageOperationStepStatus.PROCESSING) {
            step.markProcessing();
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(ImageOperationMessageView message) {
        if (consumedMessageRepository.existsById(message.messageId())) {
            return;
        }
        findMatchingStep(message).markCompletedWithoutResult();
        consumedMessageRepository.insertIfAbsent(
                message.messageId(),
                message.operationId(),
                message.stepId(),
                CONSUMER_NAME
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDecision markFailedAndSchedule(ImageOperationMessageView message, String errorMessage) {
        if (consumedMessageRepository.existsById(message.messageId())) {
            return new FailureDecision(false, message.attempt());
        }

        ImageOperationStep step = findMatchingStep(message);
        boolean exhausted = step.markFailed(errorMessage);
        ImageOperationMessageDestination destination = exhausted
                ? ImageOperationMessageDestination.DLQ
                : ImageOperationMessageDestination.RETRY;
        outboxRepository.save(outbox(step, step.getAttemptCount(), destination));
        consumedMessageRepository.insertIfAbsent(
                message.messageId(),
                message.operationId(),
                message.stepId(),
                CONSUMER_NAME
        );
        if (exhausted) {
            operationRepository.findById(message.operationId()).orElseThrow().markDlq();
        }
        return new FailureDecision(exhausted, step.getAttemptCount());
    }

    private ImageOperationStep findMatchingStep(ImageOperationMessageView message) {
        ImageOperationStep step = stepRepository.findById(message.stepId()).orElseThrow();
        if (!step.getOperationId().equals(message.operationId())
                || step.getStepType() != message.stepType()
                || !step.getTargetKey().equals(message.targetKey())) {
            throw new IllegalArgumentException("Image operation message does not match persisted step");
        }
        return step;
    }

    private ImageOperationPublishOutbox outbox(
            ImageOperationStep step,
            int attempt,
            ImageOperationMessageDestination destination
    ) {
        return ImageOperationPublishOutbox.create(
                step.getOperationId(),
                step.getStepId(),
                step.getStepType(),
                step.getTargetKey(),
                attempt,
                destination
        );
    }

    public record ImageOperationMessageView(
            UUID messageId,
            UUID operationId,
            UUID stepId,
            ImageOperationStepType stepType,
            String targetKey,
            int attempt
    ) {
    }

    public record FailureDecision(boolean exhausted, int attempt) {
    }
}
