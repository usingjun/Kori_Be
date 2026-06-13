package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageOperationPayload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImageOperationPayloadRepository extends JpaRepository<ImageOperationPayload, UUID> {
}
