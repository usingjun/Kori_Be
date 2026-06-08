package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageOperationStep;
import core.global.enums.common.ImageOperationStepType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ImageOperationStepRepository extends JpaRepository<ImageOperationStep, UUID> {

    Optional<ImageOperationStep> findByOperationIdAndStepTypeAndTargetKey(
            UUID operationId,
            ImageOperationStepType stepType,
            String targetKey
    );
}
