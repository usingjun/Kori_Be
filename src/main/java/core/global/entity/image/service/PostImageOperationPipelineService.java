package core.global.entity.image.service;

import core.domain.post.repository.PostRepository;
import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.*;
import core.global.entity.image.repository.*;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.ImageModerationStatus;
import core.global.enums.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PostImageOperationPipelineService {

    private static final String CONSUMER_NAME = "image-operation-step-consumer";
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final ImageOperationRepository operationRepository;
    private final ImageOperationStepRepository stepRepository;
    private final ImageOperationPayloadRepository payloadRepository;
    private final ImageOperationPublishOutboxRepository outboxRepository;
    private final ImageOperationConsumedMessageRepository consumedMessageRepository;
    private final ImageRepository imageRepository;
    private final PostRepository postRepository;
    private final ImageStorageClient storageClient;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Transactional
    public void scheduleCreate(Long postId, List<String> requestedKeysOrUrls) {
        if (requestedKeysOrUrls == null || requestedKeysOrUrls.isEmpty()) {
            return;
        }

        List<ImageOperation> operations = new ArrayList<>();
        List<ImageOperationPayload> payloads = new ArrayList<>();
        List<ImageOperationStep> steps = new ArrayList<>();
        List<ImageOperationPublishOutbox> outboxes = new ArrayList<>();

        for (int order = 0; order < requestedKeysOrUrls.size(); order++) {
            String sourceKey = UrlUtil.toKeyFromUrlOrKey(
                    endPoint, bucket, cdnBaseUrl, requestedKeysOrUrls.get(order)
            );
            String targetKey = finalKey(postId, order, sourceKey);
            String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, targetKey);

            ImageOperation operation = ImageOperation.create(
                    ImageOperationType.CREATE_POST_IMAGES,
                    ImageOperationOwnerType.POST,
                    postId
            );
            operation.markProcessing();
            ImageOperationStep step = requiresCopy(sourceKey, targetKey)
                    ? ImageOperationStep.createCopyStep(
                            operation.getOperationId(), sourceKey, targetKey, null, null, DEFAULT_MAX_ATTEMPTS
                    )
                    : ImageOperationStep.createRegisterImageDbStep(
                            operation.getOperationId(), targetKey, DEFAULT_MAX_ATTEMPTS
                    );

            operations.add(operation);
            payloads.add(ImageOperationPayload.create(
                    operation.getOperationId(), ImageType.POST, finalUrl, order
            ));
            steps.add(step);
            outboxes.add(outbox(step, ImageOperationMessageDestination.INITIAL));
        }

        operationRepository.saveAll(operations);
        payloadRepository.saveAll(payloads);
        stepRepository.saveAll(steps);
        outboxRepository.saveAll(outboxes);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeCopyAndScheduleRegistration(
            ImageOperationRecoveryService.ImageOperationMessageView message,
            String resultETag
    ) {
        if (consumedMessageRepository.existsById(message.messageId())) {
            return;
        }
        ImageOperationStep copyStep = matchingStep(message);
        copyStep.markCompleted(resultETag);

        ImageOperationStep registerStep = stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                message.operationId(), ImageOperationStepType.REGISTER_IMAGE_DB, copyStep.getTargetKey()
        ).orElseGet(() -> stepRepository.save(ImageOperationStep.createRegisterImageDbStep(
                message.operationId(), copyStep.getTargetKey(), DEFAULT_MAX_ATTEMPTS
        )));
        if (registerStep.getStatus() == ImageOperationStepStatus.PENDING) {
            outboxRepository.save(outbox(registerStep, ImageOperationMessageDestination.INITIAL));
        }
        recordConsumed(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerImageAndScheduleStagingDelete(
            ImageOperationRecoveryService.ImageOperationMessageView message
    ) {
        if (consumedMessageRepository.existsById(message.messageId())) {
            return;
        }
        ImageOperationStep registerStep = matchingStep(message);
        ImageOperation operation = operationRepository.findById(message.operationId()).orElseThrow();
        ImageOperationPayload payload = payloadRepository.findById(message.operationId()).orElseThrow();
        if (!postRepository.existsById(operation.getOwnerId())) {
            throw new IllegalStateException("Post no longer exists for image registration");
        }

        if (!imageRepository.existsByImageTypeAndRelatedIdAndUrl(
                payload.getImageType(), operation.getOwnerId(), payload.getFinalUrl()
        )) {
            Image saved = imageRepository.save(Image.of(
                    payload.getImageType(),
                    operation.getOwnerId(),
                    payload.getFinalUrl(),
                    payload.getOrderIndex(),
                    ImageModerationStatus.CLEAN,
                    null
            ));
            imageRepository.flush();
            eventPublisher.publishEvent(new ImageModerationEvent(saved.getId(), registerStep.getTargetKey()));
        }

        registerStep.markCompletedWithoutResult();
        ImageOperationStep copyStep = stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                message.operationId(), ImageOperationStepType.COPY_STAGING_TO_FINAL, registerStep.getTargetKey()
        ).orElse(null);
        if (copyStep == null || copyStep.getSourceKey() == null || copyStep.getSourceKey().isBlank()) {
            operation.markCompleted();
            recordConsumed(message);
            return;
        }

        ImageOperationStep deleteStep = stepRepository.findByOperationIdAndStepTypeAndTargetKey(
                message.operationId(), ImageOperationStepType.DELETE_STAGING, copyStep.getSourceKey()
        ).orElseGet(() -> stepRepository.save(ImageOperationStep.createDeleteStagingStep(
                message.operationId(), copyStep.getSourceKey(), DEFAULT_MAX_ATTEMPTS
        )));
        if (deleteStep.getStatus() == ImageOperationStepStatus.PENDING) {
            outboxRepository.save(outbox(deleteStep, ImageOperationMessageDestination.INITIAL));
        }
        recordConsumed(message);
    }

    private ImageOperationStep matchingStep(ImageOperationRecoveryService.ImageOperationMessageView message) {
        ImageOperationStep step = stepRepository.findById(message.stepId()).orElseThrow();
        if (!step.getOperationId().equals(message.operationId())
                || step.getStepType() != message.stepType()
                || !step.getTargetKey().equals(message.targetKey())) {
            throw new IllegalArgumentException("Image operation message does not match persisted step");
        }
        return step;
    }

    private void recordConsumed(ImageOperationRecoveryService.ImageOperationMessageView message) {
        consumedMessageRepository.insertIfAbsent(
                message.messageId(), message.operationId(), message.stepId(), CONSUMER_NAME
        );
    }

    private ImageOperationPublishOutbox outbox(
            ImageOperationStep step,
            ImageOperationMessageDestination destination
    ) {
        return ImageOperationPublishOutbox.create(
                step.getOperationId(),
                step.getStepId(),
                step.getStepType(),
                step.getTargetKey(),
                step.getAttemptCount(),
                destination
        );
    }

    private boolean requiresCopy(String sourceKey, String targetKey) {
        return storageClient.isStagingKey(sourceKey) && !sourceKey.equals(targetKey);
    }

    private String finalKey(Long postId, int order, String sourceKey) {
        if (!storageClient.isStagingKey(sourceKey)) {
            return sourceKey;
        }
        String basename = sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
        return "posts/%d/%03d_%s".formatted(postId, order, basename);
    }
}
