package core.global.entity.image.rabbitmq;

import core.global.entity.image.entity.ImageOperationPublishOutbox;
import core.global.enums.common.ImageOperationStepType;

import java.time.Instant;
import java.util.UUID;

public record ImageOperationMessage(
        UUID messageId,
        UUID operationId,
        UUID stepId,
        ImageOperationStepType stepType,
        String targetKey,
        int attempt,
        int schemaVersion,
        Instant occurredAt
) {
    public static ImageOperationMessage from(ImageOperationPublishOutbox outbox) {
        return new ImageOperationMessage(
                outbox.getMessageId(),
                outbox.getOperationId(),
                outbox.getStepId(),
                outbox.getStepType(),
                outbox.getTargetKey(),
                outbox.getAttempt(),
                1,
                Instant.now()
        );
    }
}
