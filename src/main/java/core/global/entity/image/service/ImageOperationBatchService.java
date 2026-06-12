package core.global.entity.image.service;

import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageOperationBatchService {

    private final ImageOperationService imageOperationService;
    private final ImageOperationStepService imageOperationStepService;
    private final ImageOperationRecoveryService imageOperationRecoveryService;
    private final ImageOperationBatchTransactionService batchTransactionService;
    private final ImageCopyExecutor imageCopyExecutor;
    private final ImageStorageClient storageClient;

    @Value("${image.cleanup.rabbit.enabled:false}")
    private boolean imageOperationRabbitEnabled;

    public TrackedCopy copy(
            ImageOperationType operationType,
            ImageOperationOwnerType ownerType,
            Long ownerId,
            String sourceKey,
            String targetKey
    ) {
        ImageOperationService.CopyOperationPlan plan = imageOperationService.createCopyOperation(
                operationType, ownerType, ownerId, sourceKey, targetKey, null, null
        );
        imageOperationService.markProcessing(plan.operationId());
        imageOperationStepService.markProcessing(plan.stepId());

        try {
            ImageCopyExecutor.ImageCopyResult result = imageCopyExecutor.copy(sourceKey, plan.targetKey());
            imageOperationStepService.markCompleted(plan.stepId(), result.resultETag());
            return new TrackedCopy(plan.operationId(), sourceKey, plan.targetKey());
        } catch (RuntimeException e) {
            imageOperationStepService.markTerminalFailed(plan.stepId(), e.getMessage());
            imageOperationService.markFailed(plan.operationId());
            compensate(new TrackedCopy(plan.operationId(), sourceKey, plan.targetKey()));
            throw e;
        }
    }

    public List<TrackedCopy> copyAll(
            ImageOperationType operationType,
            ImageOperationOwnerType ownerType,
            Long ownerId,
            List<CopyRequest> requests
    ) {
        if (requests.isEmpty()) {
            return List.of();
        }

        List<ImageOperationBatchTransactionService.CopyPlan> plans =
                batchTransactionService.prepareCopies(operationType, ownerType, ownerId, requests);
        List<ImageOperationBatchTransactionService.CopyResult> results = new java.util.ArrayList<>(plans.size());
        List<TrackedCopy> completedCopies = new java.util.ArrayList<>(plans.size());

        for (int index = 0; index < plans.size(); index++) {
            ImageOperationBatchTransactionService.CopyPlan plan = plans.get(index);
            try {
                ImageCopyExecutor.ImageCopyResult result = imageCopyExecutor.copy(plan.sourceKey(), plan.targetKey());
                results.add(new ImageOperationBatchTransactionService.CopyResult(plan.stepId(), result.resultETag()));
                completedCopies.add(new TrackedCopy(plan.operationId(), plan.sourceKey(), plan.targetKey()));
            } catch (RuntimeException e) {
                try {
                    batchTransactionService.recordFailure(
                            results,
                            plan,
                            plans.subList(index + 1, plans.size()),
                            e.getMessage()
                    );
                } catch (RuntimeException stateError) {
                    log.error("[ImageBatch] copy failure state record failed operationId={} stepId={}",
                            plan.operationId(), plan.stepId(), stateError);
                }
                List<TrackedCopy> compensationTargets = new java.util.ArrayList<>(completedCopies);
                compensationTargets.add(new TrackedCopy(plan.operationId(), plan.sourceKey(), plan.targetKey()));
                compensate(compensationTargets);
                throw e;
            }
        }

        try {
            batchTransactionService.completeCopies(results);
            return completedCopies;
        } catch (RuntimeException e) {
            try {
                batchTransactionService.recordCompletionFailure(plans, e.getMessage());
            } catch (RuntimeException stateError) {
                log.error("[ImageBatch] copy completion failure state record failed operationCount={}",
                        plans.size(), stateError);
            }
            compensate(plans.stream()
                    .map(plan -> new TrackedCopy(plan.operationId(), plan.sourceKey(), plan.targetKey()))
                    .toList());
            throw e;
        }
    }

    public void registerRollbackCompensation(List<TrackedCopy> copies) {
        if (copies.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    compensate(copies);
                }
            }
        });
    }

    public void compensate(List<TrackedCopy> copies) {
        copies.forEach(this::compensate);
    }

    private void compensate(TrackedCopy copy) {
        if (!imageOperationRabbitEnabled) {
            try {
                imageOperationService.markFailed(copy.operationId());
                storageClient.deleteObjectsBulk(List.of(copy.targetKey()));
                imageOperationService.markCompensated(copy.operationId());
            } catch (RuntimeException e) {
                log.error("[ImageBatch] direct compensation failed operationId={} targetKey={}",
                        copy.operationId(), copy.targetKey(), e);
            }
            return;
        }

        try {
            imageOperationRecoveryService.scheduleCompensation(copy.operationId(), copy.targetKey());
        } catch (RuntimeException e) {
            log.error("[ImageBatch] compensation record failed operationId={} targetKey={}",
                    copy.operationId(), copy.targetKey(), e);
        }
    }

    public void scheduleCleanup(
            ImageOperationOwnerType ownerType,
            Long ownerId,
            List<TrackedCopy> copies,
            List<String> additionalCleanupKeys
    ) {
        List<String> cleanupKeys = additionalCleanupKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .filter(key -> !storageClient.isDefaultUrlOrKey(key))
                .distinct()
                .toList();

        if (!imageOperationRabbitEnabled) {
            List<String> directCleanupKeys = java.util.stream.Stream.concat(
                            copies.stream().map(TrackedCopy::sourceKey),
                            cleanupKeys.stream()
                    )
                    .distinct()
                    .toList();
            scheduleDirectCleanupAfterCommit(directCleanupKeys, copies);
            return;
        }

        for (TrackedCopy copy : copies) {
            imageOperationRecoveryService.scheduleDeleteObjects(
                    copy.operationId(), ownerType, ownerId, List.of(copy.sourceKey())
            );
        }
        imageOperationRecoveryService.scheduleDeleteObjects(null, ownerType, ownerId, cleanupKeys);
    }

    private void scheduleDirectCleanupAfterCommit(List<String> cleanupKeys, List<TrackedCopy> copies) {
        runAfterCommit(() -> {
            try {
                storageClient.deleteObjectsBulk(cleanupKeys);
            } catch (RuntimeException e) {
                log.error("[ImageBatch] direct cleanup failed keys={}", cleanupKeys, e);
            }
            copies.forEach(copy -> {
                try {
                    imageOperationService.markCompleted(copy.operationId());
                } catch (RuntimeException e) {
                    log.error("[ImageBatch] operation completion failed operationId={}", copy.operationId(), e);
                }
            });
        });
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    public record TrackedCopy(UUID operationId, String sourceKey, String targetKey) {
    }

    public record CopyRequest(String sourceKey, String targetKey) {
    }
}
