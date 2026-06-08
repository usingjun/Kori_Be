package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageOperationService {

    private static final int DEFAULT_COPY_MAX_ATTEMPTS = 5;

    private final ImageOperationRepository operationRepository;
    private final ImageOperationStepRepository stepRepository;

    @Transactional
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

    public record CopyOperationPlan(
            java.util.UUID operationId,
            java.util.UUID stepId,
            String targetKey
    ) {
    }
}
