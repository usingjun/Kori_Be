package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperation;
import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationRepository;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationStatus;
import core.global.enums.common.ImageOperationStepStatus;
import core.global.enums.common.ImageOperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageOperationBatchTransactionServiceTest {

    @Mock
    private ImageOperationRepository operationRepository;
    @Mock
    private ImageOperationStepRepository stepRepository;

    @InjectMocks
    private ImageOperationBatchTransactionService service;

    @Test
    void prepareCopiesSavesAllOperationsAndStepsAsProcessing() {
        List<ImageOperationBatchTransactionService.CopyPlan> plans = service.prepareCopies(
                ImageOperationType.CREATE_POST_IMAGES,
                ImageOperationOwnerType.POST,
                10L,
                List.of(
                        new ImageOperationBatchService.CopyRequest("temp/a.jpg", "posts/10/a.jpg"),
                        new ImageOperationBatchService.CopyRequest("temp/b.jpg", "posts/10/b.jpg")
                )
        );

        ArgumentCaptor<List<ImageOperation>> operationsCaptor = operationsCaptor();
        ArgumentCaptor<List<ImageOperationStep>> stepsCaptor = stepsCaptor();
        verify(operationRepository).saveAll(operationsCaptor.capture());
        verify(stepRepository).saveAll(stepsCaptor.capture());

        assertThat(plans).hasSize(2);
        assertThat(operationsCaptor.getValue())
                .allMatch(operation -> operation.getStatus() == ImageOperationStatus.PROCESSING);
        assertThat(stepsCaptor.getValue())
                .allMatch(step -> step.getStatus() == ImageOperationStepStatus.PROCESSING);
    }

    @Test
    void recordFailureCompletesPreviousStepsAndFailsCurrentStepAndOperation() {
        ImageOperation completedOperation = ImageOperation.create(
                ImageOperationType.CREATE_POST_IMAGES, ImageOperationOwnerType.POST, 10L
        );
        completedOperation.markProcessing();
        ImageOperationStep completedStep = ImageOperationStep.createCopyStep(
                completedOperation.getOperationId(), "temp/a.jpg", "posts/10/a.jpg", null, null, 5
        );
        completedStep.markProcessing();

        ImageOperation failedOperation = ImageOperation.create(
                ImageOperationType.CREATE_POST_IMAGES, ImageOperationOwnerType.POST, 10L
        );
        failedOperation.markProcessing();
        ImageOperationStep failedStep = ImageOperationStep.createCopyStep(
                failedOperation.getOperationId(), "temp/b.jpg", "posts/10/b.jpg", null, null, 5
        );
        failedStep.markProcessing();

        when(stepRepository.findAllById(any())).thenReturn(List.of(completedStep));
        when(stepRepository.findById(failedStep.getStepId())).thenReturn(Optional.of(failedStep));
        when(operationRepository.findById(failedOperation.getOperationId())).thenReturn(Optional.of(failedOperation));

        service.recordFailure(
                List.of(new ImageOperationBatchTransactionService.CopyResult(completedStep.getStepId(), "etag-a")),
                new ImageOperationBatchTransactionService.CopyPlan(
                        failedOperation.getOperationId(),
                        failedStep.getStepId(),
                        "temp/b.jpg",
                        "posts/10/b.jpg"
                ),
                List.of(),
                "timeout"
        );

        assertThat(completedStep.getStatus()).isEqualTo(ImageOperationStepStatus.COMPLETED);
        assertThat(failedStep.getStatus()).isEqualTo(ImageOperationStepStatus.FAILED);
        assertThat(failedOperation.getStatus()).isEqualTo(ImageOperationStatus.FAILED);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<ImageOperation>> operationsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<ImageOperationStep>> stepsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
