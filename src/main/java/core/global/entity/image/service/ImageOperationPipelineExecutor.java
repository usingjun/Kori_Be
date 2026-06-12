package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationStepRepository;
import core.global.enums.common.ImageOperationStepType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImageOperationPipelineExecutor {

    private final ImageOperationStepRepository stepRepository;
    private final ImageCopyExecutor imageCopyExecutor;

    public ImageCopyExecutor.ImageCopyResult copy(UUID stepId) {
        ImageOperationStep step = stepRepository.findById(stepId).orElseThrow();
        if (step.getStepType() != ImageOperationStepType.COPY_STAGING_TO_FINAL) {
            throw new IllegalArgumentException("Copy executor requires COPY_STAGING_TO_FINAL step");
        }
        return imageCopyExecutor.copy(step.getSourceKey(), step.getTargetKey());
    }
}
