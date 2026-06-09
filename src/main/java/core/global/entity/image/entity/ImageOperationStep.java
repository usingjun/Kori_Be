package core.global.entity.image.entity;

import core.global.enums.common.ImageOperationStepStatus;
import core.global.enums.common.ImageOperationStepType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "image_operation_step",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_image_operation_step_target",
                columnNames = {"operation_id", "step_type", "target_key"}
        ),
        indexes = {
                @Index(name = "idx_image_operation_step_operation", columnList = "operation_id, created_at"),
                @Index(name = "idx_image_operation_step_status", columnList = "status, updated_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageOperationStep {

    @Id
    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 50)
    private ImageOperationStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageOperationStepStatus status;

    @Column(name = "source_key", length = 500)
    private String sourceKey;

    @Column(name = "target_key", length = 500)
    private String targetKey;

    @Column(name = "source_etag", length = 255)
    private String sourceETag;

    @Column(name = "source_content_length")
    private Long sourceContentLength;

    @Column(name = "result_etag", length = 255)
    private String resultETag;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static ImageOperationStep createCopyStep(
            UUID operationId,
            String sourceKey,
            String targetKey,
            String sourceETag,
            Long sourceContentLength,
            int maxAttempts
    ) {
        requirePositiveAttempts(maxAttempts);
        if (sourceKey == null || sourceKey.isBlank() || targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Copy step requires sourceKey and targetKey");
        }
        LocalDateTime now = LocalDateTime.now();
        ImageOperationStep step = new ImageOperationStep();
        step.stepId = UUID.randomUUID();
        step.operationId = Objects.requireNonNull(operationId);
        step.stepType = ImageOperationStepType.COPY_STAGING_TO_FINAL;
        step.status = ImageOperationStepStatus.PENDING;
        step.sourceKey = sourceKey;
        step.targetKey = targetKey;
        step.sourceETag = sourceETag;
        step.sourceContentLength = sourceContentLength;
        step.attemptCount = 0;
        step.maxAttempts = maxAttempts;
        step.version = 0L;
        step.createdAt = now;
        step.updatedAt = now;
        return step;
    }

    public static ImageOperationStep createCompensationStep(
            UUID operationId,
            String targetKey,
            int maxAttempts
    ) {
        requirePositiveAttempts(maxAttempts);
        LocalDateTime now = LocalDateTime.now();
        ImageOperationStep step = new ImageOperationStep();
        step.stepId = UUID.randomUUID();
        step.operationId = Objects.requireNonNull(operationId);
        step.stepType = ImageOperationStepType.COMPENSATE_FINAL_OBJECT;
        step.status = ImageOperationStepStatus.PENDING;
        step.targetKey = requireText(targetKey, "targetKey");
        step.attemptCount = 0;
        step.maxAttempts = maxAttempts;
        step.version = 0L;
        step.createdAt = now;
        step.updatedAt = now;
        return step;
    }

    public void markProcessing() {
        if (status != ImageOperationStepStatus.PENDING && status != ImageOperationStepStatus.RETRY_WAITING) {
            throw new IllegalStateException("Copy step cannot start from status " + status);
        }
        status = ImageOperationStepStatus.PROCESSING;
        updatedAt = LocalDateTime.now();
    }

    public void markCompleted(String resultETag) {
        if (status == ImageOperationStepStatus.COMPLETED) return;
        if (status != ImageOperationStepStatus.PROCESSING) {
            throw new IllegalStateException("Copy step must be PROCESSING before completion");
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageOperationStepStatus.COMPLETED;
        this.resultETag = requireText(resultETag, "resultETag");
        lastError = null;
        completedAt = now;
        updatedAt = now;
    }

    public void markCompletedWithoutResult() {
        if (status == ImageOperationStepStatus.COMPLETED) return;
        if (status != ImageOperationStepStatus.PROCESSING
                && status != ImageOperationStepStatus.RETRY_WAITING
                && status != ImageOperationStepStatus.DLQ) {
            throw new IllegalStateException("Recoverable image operation step cannot complete from status " + status);
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageOperationStepStatus.COMPLETED;
        lastError = null;
        completedAt = now;
        updatedAt = now;
    }

    public boolean markFailed(String errorMessage) {
        if (status != ImageOperationStepStatus.PROCESSING) {
            throw new IllegalStateException("Copy step must be PROCESSING before failure");
        }
        attemptCount++;
        lastError = trimError(errorMessage);
        updatedAt = LocalDateTime.now();
        if (attemptCount >= maxAttempts) {
            status = ImageOperationStepStatus.DLQ;
            return true;
        }
        status = ImageOperationStepStatus.RETRY_WAITING;
        return false;
    }

    public void markTerminalFailed(String errorMessage) {
        if (status != ImageOperationStepStatus.PROCESSING) {
            throw new IllegalStateException("Image operation step must be PROCESSING before terminal failure");
        }
        attemptCount++;
        lastError = trimError(errorMessage);
        status = ImageOperationStepStatus.FAILED;
        updatedAt = LocalDateTime.now();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static void requirePositiveAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }

    private static String trimError(String errorMessage) {
        if (errorMessage == null) return null;
        return errorMessage.length() <= 2000 ? errorMessage : errorMessage.substring(0, 2000);
    }
}
