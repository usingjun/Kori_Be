package core.global.entity.image.service.impl;

import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.ImageModerationStatus;
import core.global.enums.PollType;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainContentImageServiceImpl implements MainContentImageService {

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

    @Async("imageExecutor")
    @Transactional
    @Override
    public void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImages, PollType pollType) {
        final List<String> adds = normalizeList(addImageUrls);
        final List<String> removes = normalizeList(removeImages);

        // 변경사항이 없으면 종료
        if (adds.isEmpty() && removes.isEmpty()) return;

        // 1) [DB 삭제] 사용자 제거 요청 처리 + S3 삭제 키 수집
        List<String> bulkDeleteKeys = deleteRemovedImagesAndCollectKeys(id, removes);

        // 2) [생존 조회] DB에 남은 이미지 조회 + Position 재정렬(0부터) + 중복 방지용 URL 집합 생성
        SurvivorContext survivorContext = loadAndReorderSurvivors(id);

        // 추가할 이미지가 없다면 여기서 S3 삭제 처리 후 종료
        if (adds.isEmpty()) {
            imageOperationBatchService.scheduleCleanup(
                    ImageOperationOwnerType.POLL, id, List.of(), bulkDeleteKeys
            );
            return;
        }

        final String basePrefix = "vote/" + id;

        CopyResult copyResult = copyNewImagesSequentially(
                id,
                adds,
                basePrefix,
                survivorContext.survivorUrls(),
                survivorContext.nextPosition()
        );
        imageOperationBatchService.registerRollbackCompensation(copyResult.trackedCopies());

        try {
            if (!copyResult.toSave().isEmpty()) {
                List<Image> savedImages = imageRepository.saveAll(copyResult.toSave());
                imageRepository.flush();
                publishModerationEvents(savedImages);
            }
            imageOperationBatchService.scheduleCleanup(
                    ImageOperationOwnerType.POLL,
                    id,
                    copyResult.trackedCopies(),
                    bulkDeleteKeys
            );
        } catch (RuntimeException e) {
            imageOperationBatchService.compensate(copyResult.trackedCopies());
            throw e;
        }
    }


    private void publishModerationEvents(List<Image> savedImages) {
        for (Image img : savedImages) {
            String key = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, img.getUrl());
            eventPublisher.publishEvent(new ImageModerationEvent(img.getId(), key));
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

                String finalKey = ensureFinalKey(postId, basePrefix, myOrder, srcKey, trackedCopies);
                String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, finalKey);
                if (!survivorUrls.contains(finalUrl)) {
                    toSave.add(Image.of(ImageType.POST, postId, finalUrl, myOrder, ImageModerationStatus.CLEAN, null));
                }
            }
            return new CopyResult(toSave, trackedCopies);
        } catch (Exception e) {
            imageOperationBatchService.compensate(trackedCopies);
            log.error("[Vote IMG] Sequential copy failed", e);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    private List<String> deleteRemovedImagesAndCollectKeys(Long postId, List<String> removes) {
        List<String> bulkDeleteKeys = new ArrayList<>();
        if (!removes.isEmpty()) {
            List<String> removeKeys = removes.stream()
                    .map(raw -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, raw))
                    .toList();
            List<String> removeUrls = removeKeys.stream()
                    .map(k -> UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, k))
                    .toList();
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, removeUrls);

            bulkDeleteKeys.addAll(
                    removeKeys.stream().filter(k -> !isDefaultUrlOrKey(k)).toList()
            );
        }
        return bulkDeleteKeys;
    }

    private SurvivorContext loadAndReorderSurvivors(Long postId) {
        List<Image> survivors = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);

        int pos = 0;
        Set<String> survivorUrls = new HashSet<>();
        for (Image img : survivors) {
            img.changePosition(pos++);

            String storedKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, img.getUrl());
            String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, storedKey);
            survivorUrls.add(finalUrl);
        }

        return new SurvivorContext(survivors, survivorUrls, pos);
    }

    private boolean isDefaultUrlOrKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return false;
        String k = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, keyOrUrl);
        k = UrlUtil.trimSlashes(k);
        return k.startsWith("default/"); // 예: default/character_03.png
    }

    private String ensureFinalKey(
            Long pollId,
            String basePrefix,
            int order,
            String srcKey,
            List<ImageOperationBatchService.TrackedCopy> trackedCopies
    ) {
        String base = basePrefix.endsWith("/") ? basePrefix.substring(0, basePrefix.length() - 1) : basePrefix;
        if (!storageClient.isStagingKey(srcKey)) return srcKey;

        String basename = srcKey.substring(srcKey.lastIndexOf('/') + 1);
        String dstKey = "%s/%03d_%s".formatted(base, order, basename);
        if (srcKey.equals(dstKey)) return srcKey;

        ImageOperationBatchService.TrackedCopy trackedCopy = imageOperationBatchService.copy(
                ImageOperationType.UPDATE_POLL_IMAGES,
                ImageOperationOwnerType.POLL,
                pollId,
                srcKey,
                dstKey
        );
        trackedCopies.add(trackedCopy);
        return trackedCopy.targetKey();
    }

    private record SurvivorContext(
            List<Image> survivors,
            Set<String> survivorUrls,
            int nextPosition
    ) {
    }

    private record CopyResult(
            List<Image> toSave,
            List<ImageOperationBatchService.TrackedCopy> trackedCopies
    ) {
    }
}
