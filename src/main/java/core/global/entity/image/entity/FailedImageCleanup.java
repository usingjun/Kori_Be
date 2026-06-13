package core.global.entity.image.entity;

import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "failed_image_cleanup",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_failed_image_cleanup_operation_target",
                        columnNames = {"operation_type", "target_key"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_failed_image_cleanup_retry",
                        columnList = "status, next_retry_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FailedImageCleanup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30)
    private ImageCleanupOperationType operationType;

    @Column(name = "target_key", nullable = false, length = 500)
    private String targetKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageCleanupStatus status = ImageCleanupStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static FailedImageCleanup create(
            ImageCleanupOperationType operationType,
            String targetKey,
            String errorMessage
    ) {
        LocalDateTime now = LocalDateTime.now();

        FailedImageCleanup cleanup = new FailedImageCleanup();
        cleanup.operationType = Objects.requireNonNull(operationType);
        cleanup.targetKey = Objects.requireNonNull(targetKey);
        cleanup.status = ImageCleanupStatus.PENDING;
        cleanup.attemptCount = 0;
        cleanup.nextRetryAt = now.plusMinutes(1);
        cleanup.lastError = trimError(errorMessage);
        cleanup.createdAt = now;
        cleanup.updatedAt = now;
        return cleanup;
    }

    public void markProcessing() {
        this.status = ImageCleanupStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markSuccess() {
        this.status = ImageCleanupStatus.SUCCESS;
        this.lastError = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = ImageCleanupStatus.FAILED;
        this.attemptCount++;
        this.lastError = trimError(errorMessage);
        this.nextRetryAt = LocalDateTime.now().plusMinutes(nextDelayMinutes());
        this.updatedAt = LocalDateTime.now();
    }

    public void refreshFailure(String errorMessage) {
        if (this.status == ImageCleanupStatus.SUCCESS) {
            this.attemptCount = 0;
        }
        this.status = ImageCleanupStatus.PENDING;
        this.lastError = trimError(errorMessage);
        this.nextRetryAt = LocalDateTime.now().plusMinutes(1);
        this.updatedAt = LocalDateTime.now();
    }

    private long nextDelayMinutes() {
        if (attemptCount <= 1) return 1;
        if (attemptCount == 2) return 5;
        if (attemptCount == 3) return 15;
        return 60;
    }

    private static String trimError(String errorMessage) {
        if (errorMessage == null) return null;
        return errorMessage.length() <= 2000 ? errorMessage : errorMessage.substring(0, 2000);
    }
}
