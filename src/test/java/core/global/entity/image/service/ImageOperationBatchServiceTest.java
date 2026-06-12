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
