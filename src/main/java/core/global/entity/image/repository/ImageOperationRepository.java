package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImageOperationRepository extends JpaRepository<ImageOperation, UUID> {
}
