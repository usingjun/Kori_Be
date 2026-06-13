package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageCleanupConsumedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImageCleanupConsumedMessageRepository extends JpaRepository<ImageCleanupConsumedMessage, UUID> {
}
