package core.global.entity.image.entity;

import core.global.enums.common.ImageType;
import core.global.enums.common.ImageUploadSessionStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "image_upload_session",
        indexes = {
                @Index(name = "idx_image_upload_session_expiry", columnList = "status, expires_at"),
                @Index(name = "idx_image_upload_session_status_updated", columnList = "status, updated_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageUploadSession {

    @Id
    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false, length = 30)
    private ImageType imageType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageUploadSessionStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ImageUploadSession issue(
            String objectKey,
            Long ownerId,
            ImageType imageType,
            LocalDateTime expiresAt
    ) {
        LocalDateTime now = LocalDateTime.now();
        ImageUploadSession session = new ImageUploadSession();
        session.uploadId = UUID.randomUUID();
        session.objectKey = requireText(objectKey, "objectKey");
        session.ownerId = Objects.requireNonNull(ownerId);
        session.imageType = Objects.requireNonNull(imageType);
        session.status = ImageUploadSessionStatus.ISSUED;
        session.expiresAt = Objects.requireNonNull(expiresAt);
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }

    public void claim(Long ownerId) {
        if (!this.ownerId.equals(ownerId)) {
            throw new IllegalStateException("Image upload session owner does not match");
        }
        if (status != ImageUploadSessionStatus.ISSUED) {
            throw new IllegalStateException("Image upload session cannot be claimed from status " + status);
        }
        status = ImageUploadSessionStatus.CLAIMED;
        updatedAt = LocalDateTime.now();
    }

    public void markRegistered() {
        if (status == ImageUploadSessionStatus.REGISTERED) return;
        if (status != ImageUploadSessionStatus.CLAIMED
                && status != ImageUploadSessionStatus.ISSUED) {
            throw new IllegalStateException("Image upload session cannot be registered from status " + status);
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageUploadSessionStatus.REGISTERED;
        registeredAt = now;
        updatedAt = now;
    }

    public void markRegisteredFromObservedUsage() {
        if (status == ImageUploadSessionStatus.REGISTERED) return;
        if (status != ImageUploadSessionStatus.ISSUED
                && status != ImageUploadSessionStatus.CLAIMED) {
            throw new IllegalStateException("Image upload session cannot be reconciled from status " + status);
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageUploadSessionStatus.REGISTERED;
        registeredAt = now;
        updatedAt = now;
    }

    public void restoreRegisteredAfterDeleteSkipped() {
        if (status != ImageUploadSessionStatus.DELETE_PENDING) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageUploadSessionStatus.REGISTERED;
        registeredAt = now;
        updatedAt = now;
    }

    public void markDeletePending() {
        if (status != ImageUploadSessionStatus.ISSUED
                && status != ImageUploadSessionStatus.DELETE_FAILED) {
            throw new IllegalStateException("Image upload session cannot be delete pending from status " + status);
        }
        status = ImageUploadSessionStatus.DELETE_PENDING;
        updatedAt = LocalDateTime.now();
    }

    public void markDeleted() {
        if (status != ImageUploadSessionStatus.DELETE_PENDING
                && status != ImageUploadSessionStatus.DELETE_FAILED) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        status = ImageUploadSessionStatus.DELETED;
        deletedAt = now;
        updatedAt = now;
    }

    public void markDeleteFailed() {
        if (status != ImageUploadSessionStatus.DELETE_PENDING) return;
        status = ImageUploadSessionStatus.DELETE_FAILED;
        updatedAt = LocalDateTime.now();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
