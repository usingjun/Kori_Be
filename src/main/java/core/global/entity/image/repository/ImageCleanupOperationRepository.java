package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageCleanupOperation;
import core.global.enums.common.ImageCleanupOperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ImageCleanupOperationRepository extends JpaRepository<ImageCleanupOperation, UUID> {

    Optional<ImageCleanupOperation> findByOperationTypeAndTargetKey(
            ImageCleanupOperationType operationType,
            String targetKey
    );
}
