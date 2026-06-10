package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageOperationStep;
import core.global.enums.common.ImageOperationStepType;
import core.global.enums.common.ImageOperationStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImageOperationStepRepository extends JpaRepository<ImageOperationStep, UUID> {

    Optional<ImageOperationStep> findByOperationIdAndStepTypeAndTargetKey(
            UUID operationId,
            ImageOperationStepType stepType,
            String targetKey
    );

    List<ImageOperationStep> findTop50ByStepTypeInAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            List<ImageOperationStepType> stepTypes,
            ImageOperationStepStatus status,
            LocalDateTime updatedAt
    );

    boolean existsByOperationIdAndStatusNot(UUID operationId, ImageOperationStepStatus status);
}
