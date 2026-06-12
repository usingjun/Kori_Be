package core.global.entity.image.service.impl;

import core.global.entity.image.entity.Image;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.ImagePersistenceTransactionService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.ImageModerationStatus;
import core.global.enums.PollType;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainContentImageServiceImpl implements MainContentImageService {

    private final ImageStorageClient storageClient;
    private final ImageOperationBatchService imageOperationBatchService;
    private final ImagePersistenceTransactionService persistenceTransactionService;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Async("imageExecutor")
    @Override
    public void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImages, PollType pollType) {
        final List<String> adds = normalizeList(addImageUrls);
        final List<String> removes = normalizeList(removeImages);

        // 변경사항이 없으면 종료
        if (adds.isEmpty() && removes.isEmpty()) return;

        ImagePersistenceTransactionService.PersistenceSnapshot snapshot =
                persistenceTransactionService.loadSnapshot(id, removes);

        if (adds.isEmpty()) {
            persistenceTransactionService.persist(
                    ImageOperationOwnerType.POLL, id, snapshot, List.of(), List.of()
            );
            return;
        }

        final String basePrefix = "vote/" + id;

        CopyResult copyResult = copyNewImagesSequentially(
                id,
                adds,
                basePrefix,
                snapshot.survivorUrls(),
                snapshot.nextPosition()
        );

        try {
            persistenceTransactionService.persist(
                    ImageOperationOwnerType.POLL,
                    id,
                    snapshot,
                    copyResult.toSave(),
                    copyResult.trackedCopies()
            );
        } catch (RuntimeException e) {
            imageOperationBatchService.compensate(copyResult.trackedCopies());
            throw e;
        }
    }

    private List<String> normalizeList(List<String> list) {
        return (list == null) ? List.of() : list;
    }

    private CopyResult copyNewImagesSequentially(
            Long postId,
            List<String> adds,
            String basePrefix,
            Set<String> survivorUrls,
            int startOrder
    ) {
        List<ImageOperationBatchService.TrackedCopy> trackedCopies = new ArrayList<>();
        List<Image> toSave = new ArrayList<>(adds.size());
        List<PendingCopy> pendingCopies = new ArrayList<>();

        try {
            for (int i = 0; i < adds.size(); i++) {
                int myOrder = startOrder + i;
                String raw = adds.get(i);
                String srcKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, raw);

                if (isDefaultUrlOrKey(srcKey)) {
                    String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, srcKey);
                    if (!survivorUrls.contains(finalUrl)) {
                        toSave.add(Image.of(ImageType.POST, postId, finalUrl, myOrder));
                    }
                    continue;
                }

                String finalKey = finalKey(basePrefix, myOrder, srcKey);
                String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, finalKey);
                if (storageClient.isStagingKey(srcKey) && !srcKey.equals(finalKey)) {
                    pendingCopies.add(new PendingCopy(srcKey, finalKey, finalUrl, myOrder));
                } else if (!survivorUrls.contains(finalUrl)) {
                    toSave.add(Image.of(ImageType.POST, postId, finalUrl, myOrder, ImageModerationStatus.CLEAN, null));
                }
            }

            trackedCopies.addAll(imageOperationBatchService.copyAll(
                    ImageOperationType.UPDATE_POLL_IMAGES,
                    ImageOperationOwnerType.POLL,
                    postId,
                    pendingCopies.stream()
                            .map(copy -> new ImageOperationBatchService.CopyRequest(copy.sourceKey(), copy.targetKey()))
                            .toList()
            ));
            for (PendingCopy pendingCopy : pendingCopies) {
                if (!survivorUrls.contains(pendingCopy.finalUrl())) {
                    toSave.add(Image.of(
                            ImageType.POST,
                            postId,
                            pendingCopy.finalUrl(),
                            pendingCopy.order(),
                            ImageModerationStatus.CLEAN,
                            null
                    ));
                }
            }
            return new CopyResult(toSave, trackedCopies);
        } catch (Exception e) {
            log.error("[Vote IMG] Sequential copy failed", e);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    private boolean isDefaultUrlOrKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return false;
        String k = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, keyOrUrl);
        k = UrlUtil.trimSlashes(k);
        return k.startsWith("default/"); // 예: default/character_03.png
    }

    private String finalKey(String basePrefix, int order, String srcKey) {
        String base = basePrefix.endsWith("/") ? basePrefix.substring(0, basePrefix.length() - 1) : basePrefix;
        if (!storageClient.isStagingKey(srcKey)) return srcKey;

        String basename = srcKey.substring(srcKey.lastIndexOf('/') + 1);
        return "%s/%03d_%s".formatted(base, order, basename);
    }

    private record CopyResult(
            List<Image> toSave,
            List<ImageOperationBatchService.TrackedCopy> trackedCopies
    ) {
    }

    private record PendingCopy(String sourceKey, String targetKey, String finalUrl, int order) {
    }
}
