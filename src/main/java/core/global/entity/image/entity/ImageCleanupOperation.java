package core.global.entity.image.entity;

import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupRabbitStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "image_cleanup_operation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_image_cleanup_operation_target",
                columnNames = {"operation_type", "target_key"}
        ),
        indexes = @Index(
                name = "idx_image_cleanup_operation_status",
                columnList = "status, updated_at"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageCleanupOperation {

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30)
    private ImageCleanupOperationType operationType;

    @Column(name = "target_key", nullable = false, length = 500)
    private String targetKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageCleanupRabbitStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static ImageCleanupOperation create(
            ImageCleanupOperationType operationType,
            String targetKey,
            String errorMessage,
            int maxAttempts
    ) {
        LocalDateTime now = LocalDateTime.now();
        ImageCleanupOperation operation = new ImageCleanupOperation();
        operation.operationId = UUID.randomUUID();
        operation.operationType = Objects.requireNonNull(operationType);
        operation.targetKey = Objects.requireNonNull(targetKey);
        operation.status = ImageCleanupRabbitStatus.PENDING;
        operation.attemptCount = 0;
        operation.maxAttempts = maxAttempts;
        operation.lastError = trimError(errorMessage);
        operation.createdAt = now;
        operation.updatedAt = now;
        return operation;
    }

    public void refresh(String errorMessage) {
        this.status = ImageCleanupRabbitStatus.PENDING;
        this.lastError = trimError(errorMessage);
        this.completedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void markProcessing() {
        this.status = ImageCleanupRabbitStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markRetryWaiting(String errorMessage) {
        this.attemptCount++;
        this.status = ImageCleanupRabbitStatus.RETRY_WAITING;
        this.lastError = trimError(errorMessage);
        this.updatedAt = LocalDateTime.now();
    }

    public void markCompleted() {
        LocalDateTime now = LocalDateTime.now();
        this.status = ImageCleanupRabbitStatus.COMPLETED;
        this.lastError = null;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markDlq(String errorMessage) {
        this.attemptCount++;
        this.status = ImageCleanupRabbitStatus.DLQ;
        this.lastError = trimError(errorMessage);
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isTerminal() {
        return status == ImageCleanupRabbitStatus.COMPLETED || status == ImageCleanupRabbitStatus.DLQ;
    }

    private static String trimError(String errorMessage) {
        if (errorMessage == null) return null;
        return errorMessage.length() <= 2000 ? errorMessage : errorMessage.substring(0, 2000);
    }
}
