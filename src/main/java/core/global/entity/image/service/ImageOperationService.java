package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageOperationService {

    private static final int DEFAULT_COPY_MAX_ATTEMPTS = 5;

    private final ImageOperationRepository operationRepository;
    private final ImageOperationStepRepository stepRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CopyOperationPlan createCopyOperation(
            ImageOperationType operationType,
            ImageOperationOwnerType ownerType,
            Long ownerId,
            String sourceKey,
            String targetKey,
            String sourceETag,
            Long sourceContentLength
    ) {
        ImageOperation operation = operationRepository.save(
                ImageOperation.create(operationType, ownerType, ownerId)
        );
        ImageOperationStep step = stepRepository.save(
                ImageOperationStep.createCopyStep(
                        operation.getOperationId(),
                        sourceKey,
                        targetKey,
                        sourceETag,
                        sourceContentLength,
                        DEFAULT_COPY_MAX_ATTEMPTS
                )
        );
        return new CopyOperationPlan(operation.getOperationId(), step.getStepId(), step.getTargetKey());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID operationId) {
        find(operationId).markProcessing();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetryWaiting(UUID operationId) {
        find(operationId).markRetryWaiting();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID operationId) {
        find(operationId).markCompleted();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID operationId) {
        find(operationId).markFailed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDlq(UUID operationId) {
        find(operationId).markDlq();
    }

    private ImageOperation find(UUID operationId) {
        return operationRepository.findById(operationId).orElseThrow();
    }

    public record CopyOperationPlan(
            UUID operationId,
            UUID stepId,
            String targetKey
    ) {
    }
}
