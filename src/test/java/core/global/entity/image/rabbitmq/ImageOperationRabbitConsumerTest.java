package core.global.entity.image.rabbitmq;

import core.global.entity.image.service.ImageObjectDeleteExecutor;
import core.global.entity.image.service.ImageCopyExecutor;
import core.global.entity.image.service.ImageOperationPipelineExecutor;
import core.global.entity.image.service.ImageOperationRecoveryService;
import core.global.entity.image.service.FailedImageCleanupService;
import core.global.entity.image.service.PostImageOperationPipelineService;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageOperationStepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageOperationRabbitConsumerTest {

    @Mock
    private ImageOperationRecoveryService recoveryService;
    @Mock
    private ImageObjectDeleteExecutor objectDeleteExecutor;
    @Mock
    private FailedImageCleanupService failedImageCleanupService;
    @Mock
    private ImageOperationPipelineExecutor pipelineExecutor;
    @Mock
    private PostImageOperationPipelineService postPipelineService;

    @InjectMocks
    private ImageOperationRabbitConsumer consumer;

    @Test
    void consume_deletesCompensationTargetAndCompletesStep() {
        ImageOperationMessage message = message();
        when(recoveryService.begin(any())).thenReturn(true);

        consumer.consume(message);

        verify(objectDeleteExecutor).deleteObject(message.targetKey());
        verify(recoveryService).markCompleted(any());
        verify(recoveryService, never()).markFailedAndSchedule(any(), any());
    }

    @Test
    void consume_deletesCleanupObjectAndCompletesStep() {
        ImageOperationMessage message = message(ImageOperationStepType.DELETE_OBJECT);
        when(recoveryService.begin(any())).thenReturn(true);

        consumer.consume(message);

        verify(objectDeleteExecutor).deleteObject(message.targetKey());
        verify(recoveryService).markCompleted(any());
    }

    @Test
    void consume_deletesCleanupFolderAndCompletesStep() {
        ImageOperationMessage message = message(ImageOperationStepType.DELETE_FOLDER);
        when(recoveryService.begin(any())).thenReturn(true);

        consumer.consume(message);

        verify(failedImageCleanupService).executeCleanup(
                ImageCleanupOperationType.DELETE_FOLDER,
                message.targetKey()
        );
        verify(recoveryService).markCompleted(any());
        verifyNoInteractions(objectDeleteExecutor);
    }

    @Test
    void consume_schedulesRetryWhenCompensationFails() {
        ImageOperationMessage message = message();
        when(recoveryService.begin(any())).thenReturn(true);
        doThrow(new IllegalStateException("delete failed"))
                .when(objectDeleteExecutor).deleteObject(message.targetKey());
        when(recoveryService.markFailedAndSchedule(any(), eq("delete failed")))
                .thenReturn(new ImageOperationRecoveryService.FailureDecision(false, 1));

        consumer.consume(message);

        verify(recoveryService).markFailedAndSchedule(any(), eq("delete failed"));
        verify(recoveryService, never()).markCompleted(any());
    }

    @Test
    void consume_skipsDuplicateMessage() {
        ImageOperationMessage message = message();
        when(recoveryService.begin(any())).thenReturn(false);

        consumer.consume(message);

        verifyNoInteractions(objectDeleteExecutor);
        verify(recoveryService, never()).markCompleted(any());
    }

    @Test
    void consume_copiesAndSchedulesRegistration() {
        ImageOperationMessage message = message(ImageOperationStepType.COPY_STAGING_TO_FINAL);
        when(recoveryService.begin(any())).thenReturn(true);
        when(pipelineExecutor.copy(message.stepId()))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult(message.targetKey(), "etag"));

        consumer.consume(message);

        verify(postPipelineService).completeCopyAndScheduleRegistration(any(), eq("etag"));
        verify(recoveryService, never()).markCompleted(any());
    }

    @Test
    void consume_registersImageAndSchedulesStagingDelete() {
        ImageOperationMessage message = message(ImageOperationStepType.REGISTER_IMAGE_DB);
        when(recoveryService.begin(any())).thenReturn(true);

        consumer.consume(message);

        verify(postPipelineService).registerImageAndScheduleStagingDelete(any());
        verify(recoveryService, never()).markCompleted(any());
    }

    private ImageOperationMessage message() {
        return message(ImageOperationStepType.COMPENSATE_FINAL_OBJECT);
    }

    private ImageOperationMessage message(ImageOperationStepType stepType) {
        return new ImageOperationMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                stepType,
                "users/10/profile.jpg",
                0,
                1,
                Instant.now()
        );
    }
}
