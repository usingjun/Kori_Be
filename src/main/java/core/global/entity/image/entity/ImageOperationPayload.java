package core.global.entity.image.entity;

import core.global.enums.common.ImageType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "image_operation_payload")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageOperationPayload {

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false, length = 50)
    private ImageType imageType;

    @Column(name = "final_url", nullable = false, length = 500)
    private String finalUrl;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static ImageOperationPayload create(
            UUID operationId,
            ImageType imageType,
            String finalUrl,
            int orderIndex
    ) {
        if (finalUrl == null || finalUrl.isBlank()) {
            throw new IllegalArgumentException("finalUrl must not be blank");
        }
        ImageOperationPayload payload = new ImageOperationPayload();
        payload.operationId = Objects.requireNonNull(operationId);
        payload.imageType = Objects.requireNonNull(imageType);
        payload.finalUrl = finalUrl;
        payload.orderIndex = orderIndex;
        payload.createdAt = LocalDateTime.now();
        return payload;
    }
}
