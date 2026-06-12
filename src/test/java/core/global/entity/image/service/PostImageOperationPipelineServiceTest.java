package core.global.entity.image.service;

import core.domain.post.repository.PostRepository;
import core.global.entity.image.entity.*;
import core.global.entity.image.repository.*;
import core.global.enums.common.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostImageOperationPipelineServiceTest {

    @Mock private ImageOperationRepository operationRepository;
    @Mock private ImageOperationStepRepository stepRepository;
    @Mock private ImageOperationPayloadRepository payloadRepository;
    @Mock private ImageOperationPublishOutboxRepository outboxRepository;
    @Mock private ImageOperationConsumedMessageRepository consumedMessageRepository;
    @Mock private ImageRepository imageRepository;
    @Mock private PostRepository postRepository;
    @Mock private ImageStorageClient storageClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PostImageOperationPipelineService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bucket", "bucket");
        ReflectionTestUtils.setField(service, "endPoint", "https://kr.object.ncloudstorage.com");
        ReflectionTestUtils.setField(service, "cdnBaseUrl", "https://cdn.example.com");
    }

    @Test
    void scheduleCreate_savesCopyStepPayloadAndOutboxTogether() {
        when(storageClient.isStagingKey("temp/a.jpg")).thenReturn(true);

        service.scheduleCreate(10L, List.of("temp/a.jpg"));

        ArgumentCaptor<List<ImageOperationStep>> stepCaptor = listCaptor();
        ArgumentCaptor<List<ImageOperationPublishOutbox>> outboxCaptor = listCaptor();
        verify(operationRepository).saveAll(any());
        verify(payloadRepository).saveAll(any());
        verify(stepRepository).saveAll(stepCaptor.capture());
        verify(outboxRepository).saveAll(outboxCaptor.capture());
        assertThat(stepCaptor.getValue()).singleElement().satisfies(step -> {
            assertThat(step.getStepType()).isEqualTo(ImageOperationStepType.COPY_STAGING_TO_FINAL);
            assertThat(step.getSourceKey()).isEqualTo("temp/a.jpg");
            assertThat(step.getTargetKey()).isEqualTo("posts/10/000_a.jpg");
        });
        assertThat(outboxCaptor.getValue()).singleElement()
                .extracting(ImageOperationPublishOutbox::getStepType)
                .isEqualTo(ImageOperationStepType.COPY_STAGING_TO_FINAL);
    }

    @Test
    void completeCopyAndScheduleRegistration_completesCopyAndCreatesNextOutbox() {
        ImageOperation operation = operation();
        ImageOperationStep copyStep = ImageOperationStep.createCopyStep(
                operation.getOperationId(), "temp/a.jpg", "posts/10/000_a.jpg", null, null, 5
        );
        copyStep.markProcessing();
        var message = message(copyStep);
        when(stepRepository.findById(copyStep.getStepId())).thenReturn(Optional.of(copyStep));
        when(stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                operation.getOperationId(), ImageOperationStepType.REGISTER_IMAGE_DB, copyStep.getTargetKey()
        )).thenReturn(Optional.empty());
        when(stepRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.completeCopyAndScheduleRegistration(message, "etag");

        assertThat(copyStep.getStatus()).isEqualTo(ImageOperationStepStatus.COMPLETED);
        verify(outboxRepository).save(argThat(outbox ->
                outbox.getStepType() == ImageOperationStepType.REGISTER_IMAGE_DB
        ));
        verify(consumedMessageRepository).insertIfAbsent(
                message.messageId(), message.operationId(), message.stepId(), "image-operation-step-consumer"
        );
    }

    @Test
    void registerImageAndScheduleStagingDelete_isIdempotentAndCreatesDeleteOutbox() {
        ImageOperation operation = operation();
        ImageOperationPayload payload = ImageOperationPayload.create(
                operation.getOperationId(), ImageType.POST, "https://cdn.example.com/posts/10/000_a.jpg", 0
        );
        ImageOperationStep copyStep = ImageOperationStep.createCopyStep(
                operation.getOperationId(), "temp/a.jpg", "posts/10/000_a.jpg", null, null, 5
        );
        ImageOperationStep registerStep = ImageOperationStep.createRegisterImageDbStep(
                operation.getOperationId(), "posts/10/000_a.jpg", 5
        );
        registerStep.markProcessing();
        var message = message(registerStep);

        when(stepRepository.findById(registerStep.getStepId())).thenReturn(Optional.of(registerStep));
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));
        when(payloadRepository.findById(operation.getOperationId())).thenReturn(Optional.of(payload));
        when(postRepository.existsById(10L)).thenReturn(true);
        when(imageRepository.existsByImageTypeAndRelatedIdAndUrl(
                ImageType.POST, 10L, payload.getFinalUrl()
        )).thenReturn(true);
        when(stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                operation.getOperationId(), ImageOperationStepType.COPY_STAGING_TO_FINAL, registerStep.getTargetKey()
        )).thenReturn(Optional.of(copyStep));
        when(stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                operation.getOperationId(), ImageOperationStepType.DELETE_STAGING, copyStep.getSourceKey()
        )).thenReturn(Optional.empty());
        when(stepRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.registerImageAndScheduleStagingDelete(message);

        verify(imageRepository, never()).save(any());
        verify(outboxRepository).save(argThat(outbox ->
                outbox.getStepType() == ImageOperationStepType.DELETE_STAGING
                        && outbox.getTargetKey().equals("temp/a.jpg")
        ));
        assertThat(registerStep.getStatus()).isEqualTo(ImageOperationStepStatus.COMPLETED);
    }

    private ImageOperation operation() {
        ImageOperation operation = ImageOperation.create(
                ImageOperationType.CREATE_POST_IMAGES, ImageOperationOwnerType.POST, 10L
        );
        operation.markProcessing();
        return operation;
    }

    private ImageOperationRecoveryService.ImageOperationMessageView message(ImageOperationStep step) {
        return new ImageOperationRecoveryService.ImageOperationMessageView(
                UUID.randomUUID(),
                step.getOperationId(),
                step.getStepId(),
                step.getStepType(),
                step.getTargetKey(),
                0
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
