package core.global.entity.image.service;

import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImagePersistenceTransactionService {

    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ImageOperationBatchService imageOperationBatchService;
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
