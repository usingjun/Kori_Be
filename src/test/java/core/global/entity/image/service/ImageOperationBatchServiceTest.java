package core.global.entity.image.service;

import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOperationBatchServiceTest {

    @Mock
    private ImageOperationService imageOperationService;
    @Mock
    private ImageOperationStepService imageOperationStepService;
    @Mock
    private ImageOperationRecoveryService imageOperationRecoveryService;
    @Mock
    private ImageOperationBatchTransactionService batchTransactionService;
    @Mock
    private ImageCopyExecutor imageCopyExecutor;
    @Mock
    private ImageStorageClient storageClient;

    @InjectMocks
    private ImageOperationBatchService batchService;

    private UUID operationId;
    private UUID stepId;

    @BeforeEach
    void setUp() {
        operationId = UUID.randomUUID();
        stepId = UUID.randomUUID();
        ReflectionTestUtils.setField(batchService, "imageOperationRabbitEnabled", true);
    }

    @Test
    void copyTracksSuccessfulCopy() {
        when(imageOperationService.createCopyOperation(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                "temp/a.jpg",
                "posts/10/a.jpg",
                null,
                null
        )).thenReturn(new ImageOperationService.CopyOperationPlan(operationId, stepId, "posts/10/a.jpg"));
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/a.jpg", "etag"));

        batchService.copy(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                "temp/a.jpg",
                "posts/10/a.jpg"
        );

        verify(imageOperationService).markProcessing(operationId);
        verify(imageOperationStepService).markProcessing(stepId);
        verify(imageOperationStepService).markCompleted(stepId, "etag");
    }

    @Test
    void copyFailureSchedulesCompensationForUncertainDestination() {
        when(imageOperationService.createCopyOperation(any(), any(), anyLong(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new ImageOperationService.CopyOperationPlan(operationId, stepId, "posts/10/a.jpg"));
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> batchService.copy(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                "temp/a.jpg",
                "posts/10/a.jpg"
        )).isInstanceOf(IllegalStateException.class);

        verify(imageOperationStepService).markTerminalFailed(stepId, "timeout");
        verify(imageOperationService).markFailed(operationId);
        verify(imageOperationRecoveryService).scheduleCompensation(operationId, "posts/10/a.jpg");
    }

    @Test
    void copyAllPreparesAndCompletesCopiesInBatch() {
        UUID secondOperationId = UUID.randomUUID();
        UUID secondStepId = UUID.randomUUID();
        List<ImageOperationBatchService.CopyRequest> requests = List.of(
                new ImageOperationBatchService.CopyRequest("temp/a.jpg", "posts/10/a.jpg"),
                new ImageOperationBatchService.CopyRequest("temp/b.jpg", "posts/10/b.jpg")
        );
        List<ImageOperationBatchTransactionService.CopyPlan> plans = List.of(
                new ImageOperationBatchTransactionService.CopyPlan(
                        operationId, stepId, "temp/a.jpg", "posts/10/a.jpg"
                ),
                new ImageOperationBatchTransactionService.CopyPlan(
                        secondOperationId, secondStepId, "temp/b.jpg", "posts/10/b.jpg"
                )
        );
        when(batchTransactionService.prepareCopies(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                requests
        )).thenReturn(plans);
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/a.jpg", "etag-a"));
        when(imageCopyExecutor.copy("temp/b.jpg", "posts/10/b.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/b.jpg", "etag-b"));

        batchService.copyAll(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                requests
        );

        verify(batchTransactionService).completeCopies(List.of(
                new ImageOperationBatchTransactionService.CopyResult(stepId, "etag-a"),
                new ImageOperationBatchTransactionService.CopyResult(secondStepId, "etag-b")
        ));
    }

    @Test
    void copyAllRecordsFailureAndCompensatesCompletedAndUncertainTargets() {
        UUID secondOperationId = UUID.randomUUID();
        UUID secondStepId = UUID.randomUUID();
        List<ImageOperationBatchService.CopyRequest> requests = List.of(
                new ImageOperationBatchService.CopyRequest("temp/a.jpg", "posts/10/a.jpg"),
                new ImageOperationBatchService.CopyRequest("temp/b.jpg", "posts/10/b.jpg")
        );
        ImageOperationBatchTransactionService.CopyPlan failedPlan =
                new ImageOperationBatchTransactionService.CopyPlan(
                        secondOperationId, secondStepId, "temp/b.jpg", "posts/10/b.jpg"
                );
        when(batchTransactionService.prepareCopies(any(), any(), anyLong(), eq(requests)))
                .thenReturn(List.of(
                        new ImageOperationBatchTransactionService.CopyPlan(
                                operationId, stepId, "temp/a.jpg", "posts/10/a.jpg"
                        ),
                        failedPlan
                ));
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/a.jpg", "etag-a"));
        when(imageCopyExecutor.copy("temp/b.jpg", "posts/10/b.jpg"))
                .thenThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> batchService.copyAll(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                requests
        )).isInstanceOf(IllegalStateException.class);

        verify(batchTransactionService).recordFailure(
                List.of(new ImageOperationBatchTransactionService.CopyResult(stepId, "etag-a")),
                failedPlan,
                List.of(),
                "timeout"
        );
        verify(imageOperationRecoveryService).scheduleCompensation(operationId, "posts/10/a.jpg");
        verify(imageOperationRecoveryService).scheduleCompensation(secondOperationId, "posts/10/b.jpg");
    }

    @Test
    void copyAllCompensatesEvenWhenFailureStateCannotBeRecorded() {
        List<ImageOperationBatchService.CopyRequest> requests = List.of(
                new ImageOperationBatchService.CopyRequest("temp/a.jpg", "posts/10/a.jpg")
        );
        ImageOperationBatchTransactionService.CopyPlan failedPlan =
                new ImageOperationBatchTransactionService.CopyPlan(
                        operationId, stepId, "temp/a.jpg", "posts/10/a.jpg"
                );
        when(batchTransactionService.prepareCopies(any(), any(), anyLong(), eq(requests)))
                .thenReturn(List.of(failedPlan));
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenThrow(new IllegalStateException("copy timeout"));
        doThrow(new IllegalStateException("db down"))
                .when(batchTransactionService)
                .recordFailure(List.of(), failedPlan, List.of(), "copy timeout");

        assertThatThrownBy(() -> batchService.copyAll(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                requests
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("copy timeout");

        verify(imageOperationRecoveryService).scheduleCompensation(operationId, "posts/10/a.jpg");
    }

    @Test
    void copyAllRecordsCompletionFailureAndCompensatesEveryCopiedTarget() {
        UUID secondOperationId = UUID.randomUUID();
        UUID secondStepId = UUID.randomUUID();
        List<ImageOperationBatchService.CopyRequest> requests = List.of(
                new ImageOperationBatchService.CopyRequest("temp/a.jpg", "posts/10/a.jpg"),
                new ImageOperationBatchService.CopyRequest("temp/b.jpg", "posts/10/b.jpg")
        );
        List<ImageOperationBatchTransactionService.CopyPlan> plans = List.of(
                new ImageOperationBatchTransactionService.CopyPlan(
                        operationId, stepId, "temp/a.jpg", "posts/10/a.jpg"
                ),
                new ImageOperationBatchTransactionService.CopyPlan(
                        secondOperationId, secondStepId, "temp/b.jpg", "posts/10/b.jpg"
                )
        );
        when(batchTransactionService.prepareCopies(any(), any(), anyLong(), eq(requests))).thenReturn(plans);
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/a.jpg", "etag-a"));
        when(imageCopyExecutor.copy("temp/b.jpg", "posts/10/b.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/b.jpg", "etag-b"));
        doThrow(new IllegalStateException("db unavailable"))
                .when(batchTransactionService)
                .completeCopies(anyList());

        assertThatThrownBy(() -> batchService.copyAll(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                requests
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db unavailable");

        verify(batchTransactionService).recordCompletionFailure(plans, "db unavailable");
        verify(imageOperationRecoveryService).scheduleCompensation(operationId, "posts/10/a.jpg");
        verify(imageOperationRecoveryService).scheduleCompensation(secondOperationId, "posts/10/b.jpg");
    }

    @Test
    void copyAllCompensatesWhenCompletionFailureStateCannotBeRecorded() {
        List<ImageOperationBatchService.CopyRequest> requests = List.of(
                new ImageOperationBatchService.CopyRequest("temp/a.jpg", "posts/10/a.jpg")
        );
        List<ImageOperationBatchTransactionService.CopyPlan> plans = List.of(
                new ImageOperationBatchTransactionService.CopyPlan(
                        operationId, stepId, "temp/a.jpg", "posts/10/a.jpg"
                )
        );
        when(batchTransactionService.prepareCopies(any(), any(), anyLong(), eq(requests))).thenReturn(plans);
        when(imageCopyExecutor.copy("temp/a.jpg", "posts/10/a.jpg"))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult("posts/10/a.jpg", "etag-a"));
        doThrow(new IllegalStateException("complete failed"))
                .when(batchTransactionService)
                .completeCopies(anyList());
        doThrow(new IllegalStateException("state db unavailable"))
                .when(batchTransactionService)
                .recordCompletionFailure(plans, "complete failed");

        assertThatThrownBy(() -> batchService.copyAll(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                requests
        )).isInstanceOf(IllegalStateException.class);

        verify(imageOperationRecoveryService).scheduleCompensation(operationId, "posts/10/a.jpg");
    }

    @Test
    void cleanupCreatesDeleteStepsForCopyAndRemovedObject() {
        ImageOperationBatchService.TrackedCopy copy =
                new ImageOperationBatchService.TrackedCopy(operationId, "temp/a.jpg", "posts/10/a.jpg");

        batchService.scheduleCleanup(
                ImageOperationOwnerType.POST,
                10L,
                List.of(copy),
                List.of("posts/10/old.jpg")
        );

        verify(imageOperationRecoveryService).scheduleDeleteObjects(
                operationId, ImageOperationOwnerType.POST, 10L, List.of("temp/a.jpg")
        );
        verify(imageOperationRecoveryService).scheduleDeleteObjects(
                null, ImageOperationOwnerType.POST, 10L, List.of("posts/10/old.jpg")
        );
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    void rollbackSchedulesCompensation() {
        ImageOperationBatchService.TrackedCopy copy =
                new ImageOperationBatchService.TrackedCopy(operationId, "temp/a.jpg", "posts/10/a.jpg");
        TransactionSynchronizationManager.initSynchronization();
        try {
            batchService.registerRollbackCompensation(List.of(copy));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(imageOperationRecoveryService).scheduleCompensation(operationId, "posts/10/a.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rabbitDisabledCleanupRunsOnlyAfterCommit() {
        ReflectionTestUtils.setField(batchService, "imageOperationRabbitEnabled", false);
        ImageOperationBatchService.TrackedCopy copy =
                new ImageOperationBatchService.TrackedCopy(operationId, "temp/a.jpg", "posts/10/a.jpg");
        TransactionSynchronizationManager.initSynchronization();
        try {
            batchService.scheduleCleanup(
                    ImageOperationOwnerType.POST,
                    10L,
                    List.of(copy),
                    List.of("posts/10/old.jpg")
            );

            verify(storageClient, never()).deleteObjectsBulk(anyList());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(storageClient).deleteObjectsBulk(List.of("temp/a.jpg", "posts/10/old.jpg"));
            verify(imageOperationService).markCompleted(operationId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
