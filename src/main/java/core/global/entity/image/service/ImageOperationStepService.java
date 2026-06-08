package core.global.entity.image.service;

import core.global.entity.image.entity.ImageOperationStep;
import core.global.entity.image.repository.ImageOperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageOperationStepService {

    private static final int DEFAULT_COMPENSATION_MAX_ATTEMPTS = 5;

    private final ImageOperationStepRepository stepRepository;

    @Transactional
    public ImageOperationStep createCompensationStep(UUID operationId, String targetKey) {
        return stepRepository.save(
                ImageOperationStep.createCompensationStep(
                        operationId,
                        targetKey,
                        DEFAULT_COMPENSATION_MAX_ATTEMPTS
                )
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID stepId) {
        find(stepId).markProcessing();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID stepId, String resultETag) {
        find(stepId).markCompleted(resultETag);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDecision markFailed(UUID stepId, String errorMessage) {
        ImageOperationStep step = find(stepId);
        boolean exhausted = step.markFailed(errorMessage);
        return new FailureDecision(exhausted, step.getAttemptCount());
    }

    private ImageOperationStep find(UUID stepId) {
        return stepRepository.findById(stepId).orElseThrow();
    }

    public record FailureDecision(boolean exhausted, int attempt) {
    }
}
