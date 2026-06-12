package core.global.entity.image.service.impl;

import core.domain.post.entity.Post;
import core.global.entity.image.S3Props;
import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.PostImageService;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.ImageModerationStatus;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostImageServiceImpl implements PostImageService {

    private final S3Client s3Client;
    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ImageOperationBatchService imageOperationBatchService;
    private final S3Presigner s3Presigner;
    private final S3Props s3Props;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier("imageExecutor")
    private final Executor imageExecutor;
    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    /**
     * ✅ Presigned URL 생성 (일괄)
     */
    @Override
    public List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        if (request.files() == null || request.files().isEmpty()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        if (request.uploadSessionId() == null || request.uploadSessionId().isBlank()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }


        List<PresignedUrlResponse> out = new ArrayList<>(request.files().size());
        for (PresignedUrlRequest.FileSpec f : request.files()) {
            out.add(generateOne(email, request.imageType(), request.uploadSessionId(), f));
        }
        return out;
    }

    private PresignedUrlResponse generateOne(
            String email,
            ImageType imageType,
            String uploadSessionId,
            PresignedUrlRequest.FileSpec fileSpec
    ) {
        String filename = fileSpec.filename();
        String contentType = (fileSpec.contentType() == null || fileSpec.contentType().isBlank())
                ? "image/jpeg"
                : fileSpec.contentType();

        String key = UrlUtil.buildRawKey(email, imageType, uploadSessionId, filename);

        // 서명에 포함할 메타데이터
        Map<String, String> meta = Map.of(
                "owner", email,
                "session", uploadSessionId,
                "image-type", imageType.name().toLowerCase()
        );

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .metadata(meta)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .putObjectRequest(putObjectRequest)
                .signatureDuration(Duration.ofMinutes(10))
                .build();

        var presigned = s3Presigner.presignPutObject(presignRequest);

        // 클라이언트가 그대로 써야 하는 헤더
        Map<String, String> clientHeaders = new LinkedHashMap<>();
        clientHeaders.put("Content-Type", contentType);
        clientHeaders.put("x-amz-meta-owner", email);
        clientHeaders.put("x-amz-meta-session", uploadSessionId);
        clientHeaders.put("x-amz-meta-image-type", imageType.name().toLowerCase());

        String publicUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, key);

        return new PresignedUrlResponse(
                key,
                presigned.url().toString(),
                "PUT",
                clientHeaders
        );
    }

    @Async("imageExecutor")
    @Override
    @Transactional
    public void savePostImages(Long postId, List<String> toAdd) throws BusinessException {
        final List<String> adds = normalizeList(toAdd);
        if (adds.isEmpty()) return;

        // 1) 이미지가 존재하면 예외
        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.POST, postId)) {
            throw new BusinessException(ImageErrorCode.POST_IMAGES_ALREADY_EXIST);
        }

        final String basePrefix = "posts/" + postId;

        CopyResult copyResult = copyNewImagesInParallel(
                postId,
                adds,
                basePrefix,
                Collections.emptySet(),
                0,
                ImageOperationType.CREATE_POST_IMAGES
        );
        imageOperationBatchService.registerRollbackCompensation(copyResult.trackedCopies());

        try {
            if (!copyResult.toSave().isEmpty()) {
                List<Image> savedImages = imageRepository.saveAll(copyResult.toSave());
                imageRepository.flush();
                publishModerationEvents(savedImages);
            }
            imageOperationBatchService.scheduleCleanup(
                    ImageOperationOwnerType.POST,
                    postId,
                    copyResult.trackedCopies(),
                    List.of()
            );
        } catch (RuntimeException e) {
            imageOperationBatchService.compensate(copyResult.trackedCopies());
            throw e;
        }
    }

    @Async("imageExecutor")
    @Override
    @Transactional
    public void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove) {
        final List<String> adds = normalizeList(toAdd);
        final List<String> removes = normalizeList(toRemove);
        if (adds.isEmpty() && removes.isEmpty()) return;

        // 1) DB 삭제 + 삭제 대상 키 수집(사용자 제거)
        List<String> bulkDeleteKeys = deleteRemovedImagesAndCollectKeys(postId, removes);

        // 2) 생존 이미지 조회 + position 재정렬 + 생존 URL 집합 생성
        SurvivorContext survivorContext = loadAndReorderSurvivors(postId);

        if (adds.isEmpty()) {
            imageOperationBatchService.scheduleCleanup(
                    ImageOperationOwnerType.POST, postId, List.of(), bulkDeleteKeys
            );
            return;
        }

        final String basePrefix = "posts/" + postId;

        CopyResult copyResult = copyNewImagesInParallel(
                postId,
                adds,
                basePrefix,
                survivorContext.survivorUrls(),
                survivorContext.nextPosition(),
                ImageOperationType.UPDATE_POST_IMAGES
        );
        imageOperationBatchService.registerRollbackCompensation(copyResult.trackedCopies());

        try {
            if (!copyResult.toSave().isEmpty()) {
                List<Image> savedImages = imageRepository.saveAll(copyResult.toSave());
                imageRepository.flush();
                publishModerationEvents(savedImages);
            }
            imageOperationBatchService.scheduleCleanup(
                    ImageOperationOwnerType.POST,
                    postId,
                    copyResult.trackedCopies(),
                    bulkDeleteKeys
            );
        } catch (RuntimeException e) {
            imageOperationBatchService.compensate(copyResult.trackedCopies());
            throw e;
        }
    }

    @Async("imageExecutor")
    @Override
    @Transactional
    public void uploadAndSavePostImages(Post post, List<MultipartFile> multipartFiles) throws IOException {

        if (multipartFiles == null || multipartFiles.isEmpty()) return;

        List<Image> newImages = new ArrayList<>();
        int orderIndex = 0;

        for (MultipartFile file : multipartFiles) {
            if (file.isEmpty()) continue;

            ImageModerationStatus status = ImageModerationStatus.CLEAN;
            String reason = null;

            String originalFileName = file.getOriginalFilename();
            String extension = "";
            if (originalFileName != null && originalFileName.contains(".")) {
                extension = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            String uniqueFileName = UUID.randomUUID() + extension;
            String s3Key = "posts/" + post.getId() + "/" + uniqueFileName;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3Props.getBucket())
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            String candidateFinalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, s3Key);

            Image image = Image.of(
                    ImageType.POST,
                    post.getId(),
                    candidateFinalUrl,
                    orderIndex++,
                    status,
                    reason
            );
            newImages.add(image);
        }

        List<Image> savedImages = imageRepository.saveAll(newImages);

        // todo: 테스트를 위해 유해성 검사 실행
        publishModerationEvents(savedImages);
    }

    @Override
    @Transactional
    public void uploadAndSavePostImagesFromUrls(Post post, List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return;

        List<Image> newImages = new ArrayList<>();
        int orderIndex = 0;

        for (String originalUrl : imageUrls) {
            try {

                String s3Key = "posts/" + post.getId() + "/" + UUID.randomUUID() + getExtension(originalUrl);
                uploadFileFromUrl(originalUrl, s3Key);
                String finalUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, s3Key);

                Image image = Image.of(
                        ImageType.POST,
                        post.getId(),
                        finalUrl,
                        orderIndex++,
                        ImageModerationStatus.CLEAN,
                        null
                );
                newImages.add(image);

            } catch (Exception e) {
                log.error("외부 이미지 업로드 실패 (건너뜀): {}", originalUrl, e);
            }
        }

        List<Image> savedImages = imageRepository.saveAll(newImages);
    }

    private void uploadFileFromUrl(String urlString, String key) throws IOException {
        java.net.URL url = new java.net.URL(urlString);
        java.net.URLConnection connection = url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (java.io.InputStream inputStream = connection.getInputStream()) {
            String contentType = connection.getContentType();
            if (contentType == null) contentType = "image/jpeg";

            long length = connection.getContentLengthLong();

            PutObjectRequest.Builder reqBuilder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .acl(software.amazon.awssdk.services.s3.model.ObjectCannedACL.PUBLIC_READ);

            if (length > 0) {
                s3Client.putObject(reqBuilder.build(),
                        software.amazon.awssdk.core.sync.RequestBody.fromInputStream(inputStream, length));
            } else {
                byte[] bytes = inputStream.readAllBytes();
                s3Client.putObject(reqBuilder.build(),
                        software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
            }
        }
    }

    private String getExtension(String url) {
        if (url.contains(".")) {
            String ext = url.substring(url.lastIndexOf("."));
            if (ext.contains("?")) ext = ext.substring(0, ext.indexOf("?"));
            if (ext.length() <= 5) return ext;
        }
        return ".jpg";
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

    private CopyResult copyNewImagesInParallel(
            Long postId,
            List<String> adds,
            String basePrefix,
            Set<String> survivorUrls,
            int startOrder,
            ImageOperationType operationType
    ) {
        var trackedCopies = new ConcurrentLinkedQueue<ImageOperationBatchService.TrackedCopy>();
        List<CompletableFuture<Image>> futures = new ArrayList<>();

        for (int i = 0; i < adds.size(); i++) {
            final int myOrder = startOrder + i;
            final String raw = adds.get(i);

            var future = CompletableFuture.supplyAsync(() -> {
                String srcKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, raw);

                if (isDefaultUrlOrKey(srcKey)) {
                    String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, srcKey);
                    if (survivorUrls.contains(finalUrl)) return null;
                    return Image.of(ImageType.POST, postId, finalUrl, myOrder);
                }

                String finalKey = ensureFinalKey(postId, operationType, basePrefix, myOrder, srcKey, trackedCopies);
                String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, finalKey);
                if (survivorUrls.contains(finalUrl)) return null;
                return Image.of(ImageType.POST, postId, finalUrl, myOrder, ImageModerationStatus.CLEAN, null);
            }, imageExecutor);
            futures.add(future);
        }

        try {
            List<Image> toSave = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
            return new CopyResult(toSave, new ArrayList<>(trackedCopies));
        } catch (Exception e) {
            imageOperationBatchService.compensate(new ArrayList<>(trackedCopies));
            log.error("[POST IMG] Parallel copy failed", e);
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
            Long postId,
            ImageOperationType operationType,
            String basePrefix,
            int order,
            String srcKey,
            Queue<ImageOperationBatchService.TrackedCopy> trackedCopies
    ) {
        String base = basePrefix.endsWith("/") ? basePrefix.substring(0, basePrefix.length() - 1) : basePrefix;
        if (!storageClient.isStagingKey(srcKey)) return srcKey;

        String basename = srcKey.substring(srcKey.lastIndexOf('/') + 1);
        String dstKey = "%s/%03d_%s".formatted(base, order, basename);
        if (srcKey.equals(dstKey)) return srcKey;

        ImageOperationBatchService.TrackedCopy trackedCopy = imageOperationBatchService.copy(
                operationType,
                ImageOperationOwnerType.POST,
                postId,
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
