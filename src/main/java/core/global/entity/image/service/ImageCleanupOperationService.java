package core.global.entity.image.service;

import core.global.entity.image.entity.ImageCleanupAuditLog;
import core.global.entity.image.entity.ImageCleanupConsumedMessage;
import core.global.entity.image.entity.ImageCleanupOperation;
import core.global.entity.image.rabbitmq.ImageCleanupMessage;
import core.global.entity.image.repository.ImageCleanupAuditLogRepository;
import core.global.entity.image.repository.ImageCleanupConsumedMessageRepository;
import core.global.entity.image.repository.ImageCleanupOperationRepository;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupRabbitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageCleanupOperationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final String CONSUMER_NAME = "image-cleanup-consumer";

    private final ImageCleanupOperationRepository operationRepository;
    private final ImageCleanupAuditLogRepository auditRepository;
    private final ImageCleanupConsumedMessageRepository consumedMessageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImageCleanupMessage registerFailure(
            ImageCleanupOperationType operationType,
            String targetKey,
            String errorMessage
    ) {
        ImageCleanupOperation operation = operationRepository
                .findByOperationTypeAndTargetKey(operationType, targetKey)
                .orElseGet(() -> operationRepository.save(
                        ImageCleanupOperation.create(operationType, targetKey, errorMessage, MAX_ATTEMPTS)
                ));

        ImageCleanupRabbitStatus fromStatus = operation.getStatus();
        operation.refresh(errorMessage);
        audit(operation, "CLEANUP_RECORDED", fromStatus, operation.getStatus(), errorMessage);
        return ImageCleanupMessage.initial(operation.getOperationId(), operationType, targetKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean begin(UUID operationId, UUID messageId) {
        if (consumedMessageRepository.existsById(messageId)) {
            return false;
        }

        ImageCleanupOperation operation = operationRepository.findById(operationId).orElse(null);
        if (operation == null || operation.isTerminal()) {
            return false;
        }

        ImageCleanupRabbitStatus fromStatus = operation.getStatus();
        operation.markProcessing();
        consumedMessageRepository.save(ImageCleanupConsumedMessage.create(messageId, operationId, CONSUMER_NAME));
        audit(operation, "CONSUME_STARTED", fromStatus, operation.getStatus(), null);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID operationId, UUID messageId) {
        ImageCleanupOperation operation = operationRepository.findById(operationId).orElseThrow();
        ImageCleanupRabbitStatus fromStatus = operation.getStatus();
        operation.markCompleted();
        audit(operation, "CLEANUP_COMPLETED", fromStatus, operation.getStatus(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDecision markFailed(UUID operationId, String errorMessage) {
        ImageCleanupOperation operation = operationRepository.findById(operationId).orElseThrow();
        ImageCleanupRabbitStatus fromStatus = operation.getStatus();
        int nextAttempt = operation.getAttemptCount() + 1;
        boolean exhausted = nextAttempt >= operation.getMaxAttempts();

        if (exhausted) {
            operation.markDlq(errorMessage);
            audit(operation, "MOVED_TO_DLQ", fromStatus, operation.getStatus(), errorMessage);
        } else {
            operation.markRetryWaiting(errorMessage);
            audit(operation, "RETRY_SCHEDULED", fromStatus, operation.getStatus(), errorMessage);
        }
        return new FailureDecision(exhausted, operation.getAttemptCount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFallbackCompleted(ImageCleanupOperationType operationType, String targetKey) {
        Optional<ImageCleanupOperation> operationOptional =
                operationRepository.findByOperationTypeAndTargetKey(operationType, targetKey);
        if (operationOptional.isEmpty()) return;

        ImageCleanupOperation operation = operationOptional.get();
        if (operation.getStatus() == ImageCleanupRabbitStatus.COMPLETED) return;

        ImageCleanupRabbitStatus fromStatus = operation.getStatus();
        operation.markCompleted();
        audit(operation, "FALLBACK_COMPLETED", fromStatus, operation.getStatus(), null);
    }

    private void audit(
            ImageCleanupOperation operation,
            String eventType,
            ImageCleanupRabbitStatus fromStatus,
            ImageCleanupRabbitStatus toStatus,
            String message
    ) {
        auditRepository.save(ImageCleanupAuditLog.create(
                operation.getOperationId(),
                eventType,
                fromStatus,
                toStatus,
                message
        ));
    }

    public record FailureDecision(boolean exhausted, int attempt) {
    }
}
