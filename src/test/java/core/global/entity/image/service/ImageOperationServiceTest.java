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
        verify(stepRepository).save(stepCaptor.capture());
        assertThat(plan.operationId()).isNotNull();
        assertThat(plan.stepId()).isNotNull();
        assertThat(plan.targetKey()).isEqualTo("users/10/profile.fixed.jpg");
        assertThat(stepCaptor.getValue().getOperationId()).isEqualTo(plan.operationId());
        assertThat(stepCaptor.getValue().getTargetKey()).isEqualTo(plan.targetKey());
        assertThat(stepCaptor.getValue().getSourceContentLength()).isEqualTo(1024L);
    }
}
