package core.global.entity.image.service;

import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.config.CustomUserDetails;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImagePersistenceTransactionService {

    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ImageOperationBatchService imageOperationBatchService;
    private final ImageUploadSessionService uploadSessionService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Transactional(readOnly = true)
    public boolean postImagesExist(Long relatedId) {
        return imageRepository.existsByImageTypeAndRelatedId(ImageType.POST, relatedId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void validateFinalPostImages(List<String> keyOrUrls) {
        keyOrUrls.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(this::finalPostKey)
                .distinct()
                .forEach(storageClient::headObject);
    }

    @Transactional
    public void saveFinalPostImages(Long postId, List<String> keyOrUrls) {
        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.POST, postId)) {
            throw new BusinessException(ImageErrorCode.POST_IMAGES_ALREADY_EXIST);
        }
        List<Image> images = finalPostImages(postId, keyOrUrls, 0);
        saveAndPublish(images);
    }

    @Transactional
    public void updateFinalPostImages(Long postId, List<String> additions, List<String> removals) {
        List<Image> currentImages = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);
        Set<String> removeUrls = removals.stream()
                .map(this::objectKey)
                .map(key -> UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, key))
                .collect(java.util.stream.Collectors.toSet());
        List<String> removeKeys = removals.stream()
                .map(this::objectKey)
                .filter(key -> !storageClient.isDefaultUrlOrKey(key))
                .distinct()
                .toList();

        List<Image> survivors = currentImages.stream()
                .filter(image -> !removeUrls.contains(image.getUrl()))
                .toList();
        if (!removeUrls.isEmpty()) {
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, removeUrls);
        }
        for (int position = 0; position < survivors.size(); position++) {
            survivors.get(position).changePosition(position);
        }

        Set<String> existingUrls = survivors.stream().map(Image::getUrl)
                .collect(java.util.stream.Collectors.toSet());
        List<Image> newImages = finalPostImages(postId, additions, survivors.size()).stream()
                .filter(image -> !existingUrls.contains(image.getUrl()))
                .toList();
        saveAndPublish(newImages);
        imageOperationBatchService.scheduleCleanup(
                ImageOperationOwnerType.POST, postId, List.of(), removeKeys
        );
    }

    @Transactional
    public void deletePostImages(Long postId) {
        List<String> objectKeys = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId)
                .stream()
                .map(Image::getUrl)
                .map(this::objectKey)
                .filter(key -> !storageClient.isDefaultUrlOrKey(key))
                .distinct()
                .toList();
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.POST, postId);
        imageOperationBatchService.scheduleCleanup(
                ImageOperationOwnerType.POST, postId, List.of(), objectKeys
        );
    }

    @Transactional(readOnly = true)
    public PersistenceSnapshot loadSnapshot(Long relatedId, List<String> removes) {
        List<String> removeKeys = removes.stream()
                .map(raw -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, raw))
                .toList();
        Set<String> removeUrls = removeKeys.stream()
                .map(key -> UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, key))
                .collect(java.util.stream.Collectors.toSet());

        List<Image> survivors = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, relatedId)
                .stream()
                .filter(image -> !removeUrls.contains(image.getUrl()))
                .toList();
        Set<String> survivorUrls = new HashSet<>();
        for (Image image : survivors) {
            String storedKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, image.getUrl());
            survivorUrls.add(UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, storedKey));
        }

        List<String> cleanupKeys = removeKeys.stream()
                .filter(key -> !storageClient.isDefaultUrlOrKey(key))
                .toList();
        return new PersistenceSnapshot(removeUrls.stream().toList(), cleanupKeys, survivorUrls, survivors.size());
    }

    @Transactional
    public void persist(
            ImageOperationOwnerType ownerType,
            Long relatedId,
            PersistenceSnapshot snapshot,
            List<Image> newImages,
            List<ImageOperationBatchService.TrackedCopy> trackedCopies
    ) {
        List<Image> currentImages = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, relatedId);
        List<Image> survivors = currentImages.stream()
                .filter(image -> !snapshot.removeUrls().contains(image.getUrl()))
                .toList();
        Set<String> currentSurvivorUrls = survivors.stream()
                .map(Image::getUrl)
                .map(url -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, url))
                .map(key -> UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, key))
                .collect(java.util.stream.Collectors.toSet());
        if (!currentSurvivorUrls.equals(snapshot.survivorUrls())) {
            throw new IllegalStateException("Image persistence snapshot is stale");
        }

        if (!snapshot.removeUrls().isEmpty()) {
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(
                    ImageType.POST,
                    relatedId,
                    snapshot.removeUrls()
            );
        }

        for (int position = 0; position < survivors.size(); position++) {
            survivors.get(position).changePosition(position);
        }

        if (!newImages.isEmpty()) {
            uploadSessionService.registerExistingSessions(
                    newImages.stream()
                            .map(Image::getUrl)
                            .map(this::objectKey)
                            .toList()
            );
            List<Image> savedImages = imageRepository.saveAll(newImages);
            imageRepository.flush();
            publishModerationEvents(savedImages);
        }

        imageOperationBatchService.registerRollbackCompensation(trackedCopies);
        imageOperationBatchService.scheduleCleanup(
                ownerType,
                relatedId,
                trackedCopies,
                snapshot.cleanupKeys()
        );
    }

    private void publishModerationEvents(List<Image> savedImages) {
        for (Image image : savedImages) {
            String key = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, image.getUrl());
            eventPublisher.publishEvent(new ImageModerationEvent(image.getId(), key));
        }
    }

    private List<Image> finalPostImages(Long postId, List<String> keyOrUrls, int startPosition) {
        List<String> keys = keyOrUrls.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(this::finalPostKey)
                .distinct()
                .toList();
        uploadSessionService.claimAndRegister(keys, currentUserId());
        List<Image> images = new java.util.ArrayList<>(keys.size());
        for (int index = 0; index < keys.size(); index++) {
            images.add(Image.of(
                    ImageType.POST,
                    postId,
                    UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, keys.get(index)),
                    startPosition + index
            ));
        }
        return images;
    }

    private String finalPostKey(String keyOrUrl) {
        String key = objectKey(keyOrUrl);
        if (key == null || !key.startsWith("posts/objects/")) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        return key;
    }

    private String objectKey(String keyOrUrl) {
        return UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, keyOrUrl);
    }

    private Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }
        throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
    }

    private void saveAndPublish(List<Image> images) {
        if (images.isEmpty()) {
            return;
        }
        List<Image> savedImages = imageRepository.saveAll(images);
        imageRepository.flush();
        publishModerationEvents(savedImages);
    }

    public record PersistenceSnapshot(
            List<String> removeUrls,
            List<String> cleanupKeys,
            Set<String> survivorUrls,
            int nextPosition
    ) {
        public static PersistenceSnapshot empty() {
            return new PersistenceSnapshot(List.of(), List.of(), Set.of(), 0);
        }
    }
}
