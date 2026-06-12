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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ImageOperationBatchTransactionService {

    private static final int DEFAULT_COPY_MAX_ATTEMPTS = 5;

    private final ImageOperationRepository operationRepository;
    private final ImageOperationStepRepository stepRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CopyPlan> prepareCopies(
            ImageOperationType operationType,
            ImageOperationOwnerType ownerType,
            Long ownerId,
            List<ImageOperationBatchService.CopyRequest> requests
    ) {
        List<ImageOperation> operations = new ArrayList<>(requests.size());
        List<ImageOperationStep> steps = new ArrayList<>(requests.size());
        List<CopyPlan> plans = new ArrayList<>(requests.size());

        for (ImageOperationBatchService.CopyRequest request : requests) {
            ImageOperation operation = ImageOperation.create(operationType, ownerType, ownerId);
            operation.markProcessing();
            ImageOperationStep step = ImageOperationStep.createCopyStep(
                    operation.getOperationId(),
                    request.sourceKey(),
                    request.targetKey(),
                    null,
                    null,
                    DEFAULT_COPY_MAX_ATTEMPTS
            );
            step.markProcessing();
            operations.add(operation);
            steps.add(step);
            plans.add(new CopyPlan(
                    operation.getOperationId(),
                    step.getStepId(),
                    request.sourceKey(),
                    request.targetKey()
            ));
        }

        operationRepository.saveAll(operations);
        stepRepository.saveAll(steps);
        return plans;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeCopies(List<CopyResult> results) {
        Map<UUID, CopyResult> resultsByStepId = results.stream()
                .collect(Collectors.toMap(CopyResult::stepId, Function.identity()));
        stepRepository.findAllById(resultsByStepId.keySet()).forEach(step -> {
            CopyResult result = resultsByStepId.get(step.getStepId());
            step.markCompleted(result.resultETag());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            List<CopyResult> completedResults,
            CopyPlan failedPlan,
            List<CopyPlan> skippedPlans,
            String errorMessage
    ) {
        completeSteps(completedResults);
        ImageOperationStep failedStep = stepRepository.findById(failedPlan.stepId()).orElseThrow();
        failedStep.markTerminalFailed(errorMessage);
        operationRepository.findById(failedPlan.operationId()).orElseThrow().markFailed();
        for (CopyPlan skippedPlan : skippedPlans) {
            ImageOperationStep skippedStep = stepRepository.findById(skippedPlan.stepId()).orElseThrow();
            skippedStep.markTerminalFailed("Copy batch aborted before execution");
            operationRepository.findById(skippedPlan.operationId()).orElseThrow().markFailed();
        }
    }

    private void completeSteps(List<CopyResult> results) {
        Map<UUID, CopyResult> resultsByStepId = results.stream()
                .collect(Collectors.toMap(CopyResult::stepId, Function.identity()));
        stepRepository.findAllById(resultsByStepId.keySet()).forEach(step -> {
            CopyResult result = resultsByStepId.get(step.getStepId());
            step.markCompleted(result.resultETag());
        });
    }

    public record CopyPlan(
            UUID operationId,
            UUID stepId,
            String sourceKey,
            String targetKey
    ) {
    }

    public record CopyResult(
            UUID stepId,
            String resultETag
    ) {
    }
}
