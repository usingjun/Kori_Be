package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationStepStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageOperationStepServiceTest {

    @Mock
    private ImageOperationStepRepository stepRepository;

    @InjectMocks
    private ImageOperationStepService stepService;

    @Test
    @DisplayName("Copy step 성공 시 결과 ETag와 COMPLETED 상태를 저장한다")
    void markCompleted_recordsResultETag() {
        ImageOperationStep step = step(5);
        step.markProcessing();
        when(stepRepository.findById(step.getStepId())).thenReturn(Optional.of(step));

        stepService.markCompleted(step.getStepId(), "\"result-etag\"");

        assertThat(step.getStatus()).isEqualTo(ImageOperationStepStatus.COMPLETED);
        assertThat(step.getResultETag()).isEqualTo("\"result-etag\"");
    }

    @Test
    @DisplayName("Copy step 실패가 최대 횟수에 도달하면 DLQ로 전환한다")
    void markFailed_movesStepToDlqAtMaxAttempts() {
        ImageOperationStep step = step(2);
        step.markProcessing();
        step.markFailed("first");
        step.markProcessing();
        when(stepRepository.findById(step.getStepId())).thenReturn(Optional.of(step));

        ImageOperationStepService.FailureDecision decision =
                stepService.markFailed(step.getStepId(), "last");

        assertThat(decision.exhausted()).isTrue();
        assertThat(decision.attempt()).isEqualTo(2);
        assertThat(step.getStatus()).isEqualTo(ImageOperationStepStatus.DLQ);
    }

    @Test
    @DisplayName("완료된 Copy step은 다시 시작할 수 없다")
    void completedStep_cannotStartAgain() {
        ImageOperationStep step = step(5);
        step.markProcessing();
        step.markCompleted("\"etag\"");

        assertThatThrownBy(step::markProcessing)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("DB 등록 최종 실패 보상을 위한 final object 삭제 step을 생성한다")
    void createCompensationStep_savesFinalObjectTarget() {
        UUID operationId = UUID.randomUUID();
        when(stepRepository.save(any(ImageOperationStep.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImageOperationStep compensation =
                stepService.createCompensationStep(operationId, "users/10/profile.fixed.jpg");

        assertThat(compensation.getOperationId()).isEqualTo(operationId);
        assertThat(compensation.getStepType())
                .isEqualTo(core.global.enums.common.ImageOperationStepType.COMPENSATE_FINAL_OBJECT);
        assertThat(compensation.getTargetKey()).isEqualTo("users/10/profile.fixed.jpg");
        assertThat(compensation.getStatus()).isEqualTo(ImageOperationStepStatus.PENDING);
        verify(stepRepository).save(compensation);
    }

    private ImageOperationStep step(int maxAttempts) {
        return ImageOperationStep.createCopyStep(
                UUID.randomUUID(),
                "temp/session/a.jpg",
                "posts/1/a.jpg",
                "\"source-etag\"",
                1024L,
                maxAttempts
        );
    }
}
