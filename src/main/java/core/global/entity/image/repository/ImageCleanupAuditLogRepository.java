package core.global.entity.image.repository;

import core.global.entity.image.entity.ImageCleanupAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageCleanupAuditLogRepository extends JpaRepository<ImageCleanupAuditLog, Long> {
}
