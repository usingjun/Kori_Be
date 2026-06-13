package core.global.entity.image.entity;

import core.global.enums.common.ImageOperationOutboxStatus;
import core.global.enums.common.ImageOperationMessageDestination;
import core.global.enums.common.ImageOperationStepType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "image_operation_publish_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageOperationPublishOutbox {

    @Id
    @Column(name = "outbox_id", nullable = false)
    private UUID outboxId;

    @Column(name = "message_id", nullable = false, unique = true)
    private UUID messageId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 50)
    private ImageOperationStepType stepType;

    @Column(name = "target_key", nullable = false, length = 500)
    private String targetKey;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageOperationMessageDestination destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImageOperationOutboxStatus status;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public static ImageOperationPublishOutbox create(
            UUID operationId,
            UUID stepId,
            ImageOperationStepType stepType,
            String targetKey,
            int attempt,
            ImageOperationMessageDestination destination
    ) {
        ImageOperationPublishOutbox outbox = new ImageOperationPublishOutbox();
        outbox.outboxId = UUID.randomUUID();
        outbox.messageId = UUID.randomUUID();
        outbox.operationId = Objects.requireNonNull(operationId);
        outbox.stepId = Objects.requireNonNull(stepId);
        outbox.stepType = Objects.requireNonNull(stepType);
        outbox.targetKey = requireText(targetKey);
        outbox.attempt = attempt;
        outbox.destination = Objects.requireNonNull(destination);
        outbox.status = ImageOperationOutboxStatus.PENDING;
        outbox.createdAt = LocalDateTime.now();
        return outbox;
    }

    public void markPublished() {
        status = ImageOperationOutboxStatus.PUBLISHED;
        lastError = null;
        publishedAt = LocalDateTime.now();
    }

    public void recordFailure(String errorMessage) {
        lastError = errorMessage == null || errorMessage.length() <= 2000
                ? errorMessage
                : errorMessage.substring(0, 2000);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("targetKey must not be blank");
        }
        return value;
    }
}
