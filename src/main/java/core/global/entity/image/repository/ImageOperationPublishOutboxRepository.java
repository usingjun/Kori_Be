package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.enums.common.ImageOperationOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImageOperationPublishOutboxRepository extends JpaRepository<ImageOperationPublishOutbox, UUID> {

    List<ImageOperationPublishOutbox> findTop50ByStatusOrderByCreatedAtAsc(ImageOperationOutboxStatus status);
}
