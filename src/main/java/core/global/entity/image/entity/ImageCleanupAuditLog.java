package core.global.entity.image.entity;

import core.global.enums.common.ImageCleanupRabbitStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "image_cleanup_audit_log",
        indexes = @Index(
                name = "idx_image_cleanup_audit_operation",
                columnList = "operation_id, created_at"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageCleanupAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long id;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ImageCleanupRabbitStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    private ImageCleanupRabbitStatus toStatus;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static ImageCleanupAuditLog create(
            UUID operationId,
            String eventType,
            ImageCleanupRabbitStatus fromStatus,
            ImageCleanupRabbitStatus toStatus,
            String message
    ) {
        ImageCleanupAuditLog audit = new ImageCleanupAuditLog();
        audit.operationId = operationId;
        audit.eventType = eventType;
        audit.fromStatus = fromStatus;
        audit.toStatus = toStatus;
        audit.message = trimMessage(message);
        audit.createdAt = LocalDateTime.now();
        return audit;
    }

    private static String trimMessage(String message) {
        if (message == null) return null;
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
