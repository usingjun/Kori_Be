package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationConsumedMessageRepository;
import core.global.entity.image.repository.ImageOperationPublishOutboxRepository;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationMessageDestination;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationStatus;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageOperationStepStatus;
import core.global.enums.common.ImageOperationStepType;
import core.global.enums.common.ImageOperationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageOperationRecoveryService {

    private static final String CONSUMER_NAME = "image-operation-step-consumer";
    private static final int DEFAULT_COMPENSATION_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_CLEANUP_MAX_ATTEMPTS = 5;
    private static final long SYSTEM_OWNER_ID = 0L;
    private static final List<ImageOperationStepType> RECOVERABLE_DELETE_STEP_TYPES = List.of(
            ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
            ImageOperationStepType.DELETE_STAGING,
            ImageOperationStepType.DELETE_OBJECT,
            ImageOperationStepType.DELETE_FOLDER
    );
    private static final List<ImageOperationStepType> RECOVERABLE_PIPELINE_STEP_TYPES = List.of(
            ImageOperationStepType.COPY_STAGING_TO_FINAL,
            ImageOperationStepType.REGISTER_IMAGE_DB
    );

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
        if (operation.getStatus() != ImageOperationStatus.DLQ) {
            operation.markFailed();
        }
    }

    @Transactional
    public UUID scheduleDeleteObjects(
            UUID existingOperationId,
            ImageOperationOwnerType ownerType,
            Long ownerId,
            List<String> targetKeys
    ) {
        List<String> cleanupKeys = targetKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (cleanupKeys.isEmpty()) {
            return existingOperationId;
        }

        ImageOperation operation;
        if (existingOperationId == null) {
            operation = operationRepository.save(
                    ImageOperation.create(
                            ImageOperationType.CLEANUP_ONLY,
                            ownerType,
                            ownerId
                    )
            );
            operation.markProcessing();
        } else {
            operation = operationRepository.findById(existingOperationId).orElseThrow();
        }

        for (String targetKey : cleanupKeys) {
            if (stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                    operation.getOperationId(),
                    ImageOperationStepType.DELETE_OBJECT,
                    targetKey
            ).isPresent()) {
                continue;
            }
            ImageOperationStep step = stepRepository.save(
                    ImageOperationStep.createDeleteObjectStep(
                            operation.getOperationId(),
                            targetKey,
                            DEFAULT_CLEANUP_MAX_ATTEMPTS
                    )
            );
            outboxRepository.save(outbox(step, 0, ImageOperationMessageDestination.INITIAL));
        }
        return operation.getOperationId();
    }

    @Transactional
    public UUID scheduleDeleteFolder(
            ImageOperationOwnerType ownerType,
            Long ownerId,
            String targetPrefix
    ) {
        ImageOperation operation = operationRepository.save(
                ImageOperation.create(
                        ImageOperationType.CLEANUP_ONLY,
                        ownerType,
                        ownerId
                )
        );
        operation.markProcessing();
        ImageOperationStep step = stepRepository.save(
                ImageOperationStep.createDeleteFolderStep(
                        operation.getOperationId(),
                        targetPrefix,
                        DEFAULT_CLEANUP_MAX_ATTEMPTS
                )
        );
        outboxRepository.save(outbox(step, 0, ImageOperationMessageDestination.INITIAL));
        return operation.getOperationId();
    }

    @Transactional
    public int scheduleUnregisteredPostObjectDeletes(List<String> targetKeys) {
        List<String> cleanupKeys = targetKeys.stream()
                .filter(key -> key != null && key.startsWith("posts/objects/"))
                .distinct()
                .toList();
        if (cleanupKeys.isEmpty()) {
            return 0;
        }

        Set<String> alreadyTracked = new HashSet<>(
                stepRepository.findByStepTypeAndTargetKeyIn(
                                ImageOperationStepType.DELETE_OBJECT,
                                cleanupKeys
                        ).stream()
                        .map(ImageOperationStep::getTargetKey)
                        .toList()
        );
        List<String> newCleanupKeys = cleanupKeys.stream()
                .filter(key -> !alreadyTracked.contains(key))
                .toList();
        if (newCleanupKeys.isEmpty()) {
            return 0;
        }

        ImageOperation operation = operationRepository.save(
                ImageOperation.create(
                        ImageOperationType.CLEANUP_ONLY,
                        ImageOperationOwnerType.SYSTEM,
                        SYSTEM_OWNER_ID
                )
        );
        operation.markProcessing();
        for (String targetKey : newCleanupKeys) {
            ImageOperationStep step = stepRepository.save(
                    ImageOperationStep.createDeleteObjectStep(
                            operation.getOperationId(),
                            targetKey,
                            DEFAULT_CLEANUP_MAX_ATTEMPTS
                    )
            );
            outboxRepository.save(outbox(step, 0, ImageOperationMessageDestination.INITIAL));
        }
        return newCleanupKeys.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID scheduleFailedCleanup(
            Long failedCleanupId,
            ImageCleanupOperationType operationType,
            String targetKey
    ) {
        ImageOperationStepType stepType = cleanupStepType(operationType);
        Optional<ImageOperationStep> latestStep =
                stepRepository.findFirstByStepTypeAndTargetKeyOrderByCreatedAtDesc(stepType, targetKey);
        if (latestStep.isPresent() && isActive(latestStep.get().getStatus())) {
            return latestStep.get().getOperationId();
        }

        ImageOperation operation = operationRepository.save(
                ImageOperation.create(
                        ImageOperationType.CLEANUP_ONLY,
                        ImageOperationOwnerType.IMAGE,
                        failedCleanupId
                )
        );
        operation.markProcessing();
        ImageOperationStep step = stepRepository.save(
                operationType == ImageCleanupOperationType.DELETE_OBJECT
                        ? ImageOperationStep.createDeleteObjectStep(
                                operation.getOperationId(), targetKey, DEFAULT_CLEANUP_MAX_ATTEMPTS
                        )
                        : ImageOperationStep.createDeleteFolderStep(
                                operation.getOperationId(), targetKey, DEFAULT_CLEANUP_MAX_ATTEMPTS
                        )
        );
        outboxRepository.save(outbox(step, 0, ImageOperationMessageDestination.INITIAL));
        return operation.getOperationId();
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
        ImageOperationStep step = findMatchingStep(message);
        step.markCompletedWithoutResult();
        ImageOperation operation = operationRepository.findById(message.operationId()).orElseThrow();
        if (step.getStepType() == ImageOperationStepType.COMPENSATE_FINAL_OBJECT) {
            operation.markCompensated();
        } else if (!stepRepository.existsByOperationIdAndStatusNot(
                message.operationId(),
                ImageOperationStepStatus.COMPLETED
        )) {
            operation.markCompleted();
        }
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverTimedOutDeleteSteps(LocalDateTime timedOutBefore) {
        List<ImageOperationStep> timedOutSteps =
                stepRepository.findTop50ByStepTypeInAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        RECOVERABLE_DELETE_STEP_TYPES,
                        ImageOperationStepStatus.PROCESSING,
                        timedOutBefore
                );

        for (ImageOperationStep step : timedOutSteps) {
            boolean exhausted = step.markFailed("Image object delete processing timeout");
            ImageOperationMessageDestination destination = exhausted
                    ? ImageOperationMessageDestination.DLQ
                    : ImageOperationMessageDestination.RETRY;
            outboxRepository.save(outbox(step, step.getAttemptCount(), destination));
            if (exhausted) {
                operationRepository.findById(step.getOperationId()).orElseThrow().markDlq();
            }
        }
        return timedOutSteps.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverTimedOutPipelineSteps(LocalDateTime timedOutBefore) {
        List<ImageOperationStep> timedOutSteps =
                stepRepository.findTop50ByStepTypeInAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        RECOVERABLE_PIPELINE_STEP_TYPES,
                        ImageOperationStepStatus.PROCESSING,
                        timedOutBefore
                );

        for (ImageOperationStep step : timedOutSteps) {
            boolean exhausted = step.markFailed("Image pipeline step processing timeout");
            ImageOperationMessageDestination destination = exhausted
                    ? ImageOperationMessageDestination.DLQ
                    : ImageOperationMessageDestination.RETRY;
            outboxRepository.save(outbox(step, step.getAttemptCount(), destination));
            if (exhausted) {
                ImageOperation operation = operationRepository.findById(step.getOperationId()).orElseThrow();
                operation.markDlq();
                createCompensationIfMissing(operation, step.getTargetKey());
            }
        }
        return timedOutSteps.size();
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

    private ImageOperationStepType cleanupStepType(ImageCleanupOperationType operationType) {
        return switch (operationType) {
            case DELETE_OBJECT -> ImageOperationStepType.DELETE_OBJECT;
            case DELETE_FOLDER -> ImageOperationStepType.DELETE_FOLDER;
        };
    }

    private boolean isActive(ImageOperationStepStatus status) {
        return status == ImageOperationStepStatus.PENDING
                || status == ImageOperationStepStatus.PROCESSING
                || status == ImageOperationStepStatus.RETRY_WAITING;
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

    private void createCompensationIfMissing(ImageOperation operation, String targetKey) {
        if (stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                operation.getOperationId(),
                ImageOperationStepType.COMPENSATE_FINAL_OBJECT,
                targetKey
        ).isPresent()) {
            return;
        }
        ImageOperationStep compensationStep = stepRepository.save(
                ImageOperationStep.createCompensationStep(
                        operation.getOperationId(),
                        targetKey,
                        DEFAULT_COMPENSATION_MAX_ATTEMPTS
                )
        );
        outboxRepository.save(outbox(compensationStep, 0, ImageOperationMessageDestination.INITIAL));
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
