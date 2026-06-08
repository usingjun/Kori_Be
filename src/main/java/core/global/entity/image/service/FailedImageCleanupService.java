package core.global.entity.image.service;

import core.global.entity.image.entity.FailedImageCleanup;
import core.global.entity.image.repository.FailedImageCleanupRepository;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FailedImageCleanupService {

    private static final int DEFAULT_BATCH_SIZE = 50;

    private final FailedImageCleanupRepository failedImageCleanupRepository;
    private final S3Client s3Client;
    private final ImageCleanupRabbitBridge imageCleanupRabbitBridge;
    private final ImageCleanupOperationService imageCleanupOperationService;

    @Value("${ncp.s3.bucket}")
    private String bucket;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeleteObject(String targetKey, String errorMessage) {
        recordFailure(ImageCleanupOperationType.DELETE_OBJECT, normalizeObjectKey(targetKey), errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeleteFolder(String targetPrefix, String errorMessage) {
        recordFailure(ImageCleanupOperationType.DELETE_FOLDER, normalizeFolderPrefix(targetPrefix), errorMessage);
    }

    @Transactional
    public int retryDueCleanups() {
        return retryDueCleanups(DEFAULT_BATCH_SIZE);
    }

    @Transactional
    public int retryDueCleanups(int batchSize) {
        List<FailedImageCleanup> targets = failedImageCleanupRepository.findRetryTargets(
                List.of(ImageCleanupStatus.PENDING, ImageCleanupStatus.FAILED),
                LocalDateTime.now(),
                Pageable.ofSize(batchSize)
        );

        int processed = 0;
        for (FailedImageCleanup cleanup : targets) {
            // PESSIMISTIC_WRITE prevents duplicate schedulers from picking the same row.
            // This keeps the S3 call inside the transaction, so lock time can grow if S3 is slow.
            cleanup.markProcessing();
            try {
                executeCleanup(cleanup.getOperationType(), cleanup.getTargetKey());
                cleanup.markSuccess();
                imageCleanupOperationService.markFallbackCompleted(
                        cleanup.getOperationType(),
                        cleanup.getTargetKey()
                );
                processed++;
            } catch (Exception e) {
                cleanup.markFailed(e.getMessage());
                log.warn("[ImageCleanup] retry failed id={} type={} target={} attempt={} err={}",
                        cleanup.getId(),
                        cleanup.getOperationType(),
                        cleanup.getTargetKey(),
                        cleanup.getAttemptCount(),
                        e.getMessage());
            }
        }
        return processed;
    }

    private void recordFailure(ImageCleanupOperationType operationType, String targetKey, String errorMessage) {
        if (targetKey == null || targetKey.isBlank()) {
            return;
        }
        if (targetKey.startsWith("default/")) {
            return;
        }

        failedImageCleanupRepository.findByOperationTypeAndTargetKey(operationType, targetKey)
                .ifPresentOrElse(
                        existing -> existing.refreshFailure(errorMessage),
                        () -> failedImageCleanupRepository.save(
                                FailedImageCleanup.create(operationType, targetKey, errorMessage)
                        )
                );
        imageCleanupRabbitBridge.recordAndPublish(operationType, targetKey, errorMessage);
    }

    public void executeCleanup(ImageCleanupOperationType operationType, String targetKey) {
        if (targetKey == null || targetKey.isBlank() || targetKey.startsWith("default/")) {
            return;
        }

        if (operationType == ImageCleanupOperationType.DELETE_OBJECT) {
            deleteObject(targetKey);
            return;
        }

        if (operationType == ImageCleanupOperationType.DELETE_FOLDER) {
            deleteFolder(targetKey);
            return;
        }

        throw new IllegalStateException("Unsupported image cleanup operation: " + operationType);
    }

    private void deleteObject(String key) {
        s3Client.deleteObject(b -> b.bucket(bucket).key(key));
    }

    private void deleteFolder(String prefix) {
        String normalizedPrefix = normalizeFolderPrefix(prefix);
        String continuation = null;

        do {
            var reqBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(normalizedPrefix);
            if (continuation != null) {
                reqBuilder.continuationToken(continuation);
            }

            var res = s3Client.listObjectsV2(reqBuilder.build());
            List<ObjectIdentifier> toDelete = res.contents().stream()
                    .map(S3Object::key)
                    .filter(k -> !k.endsWith("/"))
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();

            if (!toDelete.isEmpty()) {
                var delReq = DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).build())
                        .build();
                var delRes = s3Client.deleteObjects(delReq);
                if (delRes != null && delRes.errors() != null && !delRes.errors().isEmpty()) {
                    throw SdkException.builder()
                            .message("folder delete partial failure: " + delRes.errors().size())
                            .build();
                }
            }

            continuation = res.isTruncated() ? res.nextContinuationToken() : null;
        } while (continuation != null);
    }

    private String normalizeObjectKey(String targetKey) {
        if (targetKey == null) return null;
        return UrlUtil.trimSlashes(targetKey);
    }

    private String normalizeFolderPrefix(String targetPrefix) {
        String normalized = normalizeObjectKey(targetPrefix);
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
