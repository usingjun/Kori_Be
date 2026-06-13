package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class ImageOperationServiceTest {

    @Mock
    private ImageOperationRepository operationRepository;
    @Mock
    private ImageOperationStepRepository stepRepository;

    @InjectMocks
    private ImageOperationService operationService;

    @Test
    @DisplayName("Copy operation 생성 시 operation과 고정 targetKey를 가진 step을 함께 저장한다")
    void createCopyOperation_savesOperationAndCopyStep() {
        when(operationRepository.save(any(ImageOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.save(any(ImageOperationStep.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImageOperationService.CopyOperationPlan plan = operationService.createCopyOperation(
                ImageOperationType.UPDATE_USER_PROFILE_IMAGE,
                ImageOperationOwnerType.USER,
                10L,
                "temp/session/profile.jpg",
                "users/10/profile.fixed.jpg",
                "\"source-etag\"",
                1024L
        );

        ArgumentCaptor<ImageOperationStep> stepCaptor = ArgumentCaptor.forClass(ImageOperationStep.class);
        ArgumentCaptor<ImageOperation> operationCaptor = ArgumentCaptor.forClass(ImageOperation.class);
        verify(operationRepository).save(operationCaptor.capture());
        verify(stepRepository).save(stepCaptor.capture());
        assertThat(plan.operationId()).isNotNull();
        assertThat(plan.stepId()).isNotNull();
        assertThat(plan.targetKey()).isEqualTo("users/10/profile.fixed.jpg");
        assertThat(stepCaptor.getValue().getOperationId()).isEqualTo(plan.operationId());
        assertThat(stepCaptor.getValue().getTargetKey()).isEqualTo(plan.targetKey());
        assertThat(stepCaptor.getValue().getSourceContentLength()).isEqualTo(1024L);
        assertThat(operationCaptor.getValue().getVersion()).isNull();
        assertThat(stepCaptor.getValue().getVersion()).isNull();
    }

    @Test
    @DisplayName("직접 업로드 operation 생성 시 UPLOAD_OBJECT step과 고정 targetKey를 저장한다")
    void createUploadOperation_savesOperationAndUploadStep() {
        when(operationRepository.save(any(ImageOperation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.save(any(ImageOperationStep.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImageOperationService.UploadOperationPlan plan = operationService.createUploadOperation(
                ImageOperationType.UPLOAD_USER_PROFILE_IMAGE,
                ImageOperationOwnerType.USER,
                10L,
                "users/10/profile.direct.jpg"
        );

        ArgumentCaptor<ImageOperationStep> stepCaptor = ArgumentCaptor.forClass(ImageOperationStep.class);
        verify(stepRepository).save(stepCaptor.capture());
        assertThat(plan.operationId()).isNotNull();
        assertThat(plan.stepId()).isNotNull();
        assertThat(plan.targetKey()).isEqualTo("users/10/profile.direct.jpg");
        assertThat(stepCaptor.getValue().getStepType())
                .isEqualTo(core.global.enums.common.ImageOperationStepType.UPLOAD_OBJECT);
        assertThat(stepCaptor.getValue().getTargetKey()).isEqualTo(plan.targetKey());
    }

    @Test
    @DisplayName("재시도 대기 중인 operation은 다시 PROCESSING으로 진입할 수 있다")
    void operation_canRestartAfterRetryWaiting() {
        ImageOperation operation = ImageOperation.create(
                ImageOperationType.UPDATE_USER_PROFILE_IMAGE,
                ImageOperationOwnerType.USER,
                10L
        );
        operation.markProcessing();
        operation.markRetryWaiting();

        assertThatCode(operation::markProcessing).doesNotThrowAnyException();
        assertThat(operation.getStatus()).isEqualTo(core.global.enums.common.ImageOperationStatus.PROCESSING);
    }

    @Test
    @DisplayName("재시도 한도를 소진한 operation은 DLQ로 전환한다")
    void markDlq_movesOperationToDlq() {
        ImageOperation operation = ImageOperation.create(
                ImageOperationType.UPDATE_USER_PROFILE_IMAGE,
                ImageOperationOwnerType.USER,
                10L
        );
        operation.markProcessing();
        when(operationRepository.findById(operation.getOperationId())).thenReturn(java.util.Optional.of(operation));

        operationService.markDlq(operation.getOperationId());

        assertThat(operation.getStatus()).isEqualTo(core.global.enums.common.ImageOperationStatus.DLQ);
    }
}
