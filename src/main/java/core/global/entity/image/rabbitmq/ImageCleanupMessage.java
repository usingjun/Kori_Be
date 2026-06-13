package core.global.entity.image.rabbitmq;

import core.global.enums.common.ImageCleanupOperationType;

import java.time.Instant;
import java.util.UUID;

public record ImageCleanupMessage(
        UUID messageId,
        UUID operationId,
        ImageCleanupOperationType operationType,
        String targetKey,
        int attempt,
        int schemaVersion,
        Instant occurredAt
) {
    public static ImageCleanupMessage initial(UUID operationId, ImageCleanupOperationType operationType, String targetKey) {
        return new ImageCleanupMessage(
                UUID.randomUUID(),
                operationId,
                operationType,
                targetKey,
                0,
                1,
                Instant.now()
        );
    }

    public ImageCleanupMessage nextAttempt(int nextAttempt) {
        return new ImageCleanupMessage(
                UUID.randomUUID(),
                operationId,
                operationType,
                targetKey,
                nextAttempt,
                schemaVersion,
                Instant.now()
        );
    }
}
