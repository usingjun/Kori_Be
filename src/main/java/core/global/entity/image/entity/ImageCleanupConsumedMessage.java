package core.global.entity.image.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "image_cleanup_consumed_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageCleanupConsumedMessage {

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Column(name = "consumed_at", nullable = false)
    private LocalDateTime consumedAt;

    public static ImageCleanupConsumedMessage create(UUID messageId, UUID operationId, String consumerName) {
        ImageCleanupConsumedMessage consumed = new ImageCleanupConsumedMessage();
        consumed.messageId = messageId;
        consumed.operationId = operationId;
        consumed.consumerName = consumerName;
        consumed.consumedAt = LocalDateTime.now();
        return consumed;
    }
}
