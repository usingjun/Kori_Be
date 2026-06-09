package core.global.entity.image.entity;

import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationStatus;
import core.global.enums.common.ImageOperationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "image_operation",
        indexes = {
                @Index(name = "idx_image_operation_owner", columnList = "owner_type, owner_id, created_at"),
                @Index(name = "idx_image_operation_status", columnList = "status, updated_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageOperation {

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 50)
    private ImageOperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 30)
    private ImageOperationOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageOperationStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static ImageOperation create(
            ImageOperationType operationType,
            ImageOperationOwnerType ownerType,
            Long ownerId
    ) {
        LocalDateTime now = LocalDateTime.now();
        ImageOperation operation = new ImageOperation();
        operation.operationId = UUID.randomUUID();
        operation.operationType = Objects.requireNonNull(operationType);
        operation.ownerType = Objects.requireNonNull(ownerType);
        operation.ownerId = Objects.requireNonNull(ownerId);
        operation.status = ImageOperationStatus.PENDING;
        operation.version = 0L;
        operation.createdAt = now;
        operation.updatedAt = now;
        return operation;
    }

    public void markProcessing() {
        if (status != ImageOperationStatus.PENDING && status != ImageOperationStatus.RETRY_WAITING) {
            throw new IllegalStateException("Image operation cannot start from status " + status);
        }
        status = ImageOperationStatus.PROCESSING;
        updatedAt = LocalDateTime.now();
    }

    public void markCompleted() {
        if (status == ImageOperationStatus.COMPLETED) return;
        if (status != ImageOperationStatus.PROCESSING) {
            throw new IllegalStateException("Image operation must be PROCESSING before completion");
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageOperationStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    public void markRetryWaiting() {
        if (status != ImageOperationStatus.PROCESSING) {
            throw new IllegalStateException("Image operation must be PROCESSING before retry waiting");
        }
        status = ImageOperationStatus.RETRY_WAITING;
        updatedAt = LocalDateTime.now();
    }

    public void markFailed() {
        if (status == ImageOperationStatus.COMPLETED || status == ImageOperationStatus.DLQ) {
            throw new IllegalStateException("Terminal image operation cannot fail");
        }
        status = ImageOperationStatus.FAILED;
        updatedAt = LocalDateTime.now();
    }

    public void markDlq() {
        if (status == ImageOperationStatus.COMPLETED) {
            throw new IllegalStateException("Completed image operation cannot move to DLQ");
        }
        status = ImageOperationStatus.DLQ;
        updatedAt = LocalDateTime.now();
    }

}
