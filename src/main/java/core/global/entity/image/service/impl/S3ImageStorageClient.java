package core.global.entity.image.service.impl;

import core.global.entity.image.S3Props;
import core.global.entity.image.service.FailedImageCleanupService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import static core.global.entity.image.utils.UrlUtil.toKeyFromUrlOrKey;
import static core.global.entity.image.utils.UrlUtil.trimSlashes;
import static software.amazon.awssdk.services.s3.model.ObjectIdentifier.builder;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3ImageStorageClient implements ImageStorageClient {

    private final S3Client s3Client;
    private final S3Props s3Props;
    private final FailedImageCleanupService failedImageCleanupService;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Override
    public void deleteObjectsBulk(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;

        List<String> filtered = keys.stream()
                .filter(k -> !isDefaultUrlOrKey(k))
                .toList();
        if (filtered.isEmpty()) return;

        final int LIMIT = 1000; // S3/NCP 일반 한도
        for (int i = 0; i < filtered.size(); i += LIMIT) {
            List<String> chunk = filtered.subList(i, Math.min(i + LIMIT, filtered.size()));
            try {
                var res = s3Client.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(
                        chunk.stream()
                                .map(k -> builder().key(k).build())
                                .toList()
                )));
                if (res != null && res.errors() != null && !res.errors().isEmpty()) {
                    for (var err : res.errors()) {
                        log.warn("[POST IMG] bulk delete error key={}, code={}, msg={}",
                                err.key(), err.code(), err.message());
                        failedImageCleanupService.recordDeleteObject(err.key(), err.message());
                    }
                }
            } catch (SdkException e) {
                log.warn("[POST IMG] bulk delete failed size={}, err={}", chunk.size(), e.getMessage());
                chunk.forEach(key -> failedImageCleanupService.recordDeleteObject(key, e.getMessage()));
            }
        }
    }

    /**
     * ✅ 폴더 삭제 (prefix 기준)
     */
    @Override
    public void deleteFolder(String fileLocation) {
        String prefix = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, fileLocation);
        if (!prefix.endsWith("/")) prefix += "/";

        // prefix 자체가 default면 즉시 스킵
        if (isDefaultUrlOrKey(prefix)) return;

        String continuation = null;
        try {
            do {
                var reqBuilder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (continuation != null) reqBuilder.continuationToken(continuation);
                var res = s3Client.listObjectsV2(reqBuilder.build());

                var toDelete = res.contents().stream()
                        .map(S3Object::key)
                        .filter(k -> !k.endsWith("/"))
                        .filter(k -> !isDefaultUrlOrKey(k))
                        .map(k -> ObjectIdentifier.builder().key(k).build())
                        .collect(Collectors.toList());

                if (!toDelete.isEmpty()) {
                    var delReq = DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(toDelete).build())
                            .build();
                    var delRes = s3Client.deleteObjects(delReq);
                    if (delRes != null && delRes.errors() != null && !delRes.errors().isEmpty()) {
                        for (var err : delRes.errors()) {
                            log.warn("[POST IMG] folder delete error key={}, code={}, msg={}",
                                    err.key(), err.code(), err.message());
                            failedImageCleanupService.recordDeleteObject(err.key(), err.message());
                        }
                    }
                }

                continuation = res.isTruncated() ? res.nextContinuationToken() : null;
            } while (continuation != null);
        } catch (SdkException e) {
            failedImageCleanupService.recordDeleteFolder(prefix, e.getMessage());
            throw new BusinessException(ImageErrorCode.IMAGE_FOLDER_DELETE_FAILED);
        }
    }

    @Override
    public void deleteObjectsByUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return;

        List<String> keys = urls.stream()
                .map(url -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, url))
                .collect(Collectors.toList());
        deleteObjectsBulk(keys);
    }

    @Override
    public HeadObjectResponse headObject(String key) {
        try {
            return s3Client.headObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    @Override
    public String extOf(String key) {
        int dot = key.lastIndexOf('.');
        String ext = (dot > -1 && dot < key.length() - 1) ? key.substring(dot + 1) : "jpg";
        if (ext.length() > 8) ext = "jpg";
        return ext.toLowerCase();
    }

    @Override
    public boolean isDefaultUrlOrKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return false;
        String k = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, keyOrUrl);
        k = trimSlashes(k);
        return k.startsWith("default/"); // 예: default/character_03.png
    }

    @Override
    public boolean isStagingKey(String key) {
        String k = UrlUtil.trimSlashes(key);
        return k.startsWith("temp/");
    }

    @Override
    public String generatePublicUrl(String key) {
        return UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, key);
    }

    /**
     * [NEW] 썸네일 URL 생성
     * 규칙:
     * 1. 이미지 파일인 경우 -> NCP/AWS Image Optimizer 쿼리 스트링 추가 (선택사항) 또는 원본 리턴
     * 2. 비디오 파일인 경우 -> 확장자를 .jpg로 변경하여 리턴 (해당 파일이 S3에 존재해야 함)
     */
    @Override
    public String generateThumbnailUrl(String key) {
        if (key == null || key.isBlank()) return null;

        String ext = extOf(key);

        if (isVideoExtension(ext)) {
            String thumbKey = key.substring(0, key.lastIndexOf('.')) + ".jpg";

            return UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, thumbKey);
        }
        String originalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, key);
        return originalUrl + "?type=f&w=300&h=300&ttype=jpg";
    }

    @Override
    public String upload(MultipartFile file, String key) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(file.getContentType())
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putObjectRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return key;
        } catch (IOException e) {
            throw new BusinessException(CommonErrorCode.FILE_UPLOAD_ERROR);
        }
    }

    @Override
    public String uploadFromUrl(String imageUrl, String key) {
        try {
            URL url = new URL(imageUrl);
            try (InputStream inputStream = url.openStream()) {
                byte[] imageBytes = inputStream.readAllBytes();

                String ext = extOf(key);
                String contentType = "image/" + (ext.equals("png") ? "png" : "jpeg");

                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .contentLength((long) imageBytes.length)
                        .build();

                s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes));

                return key;
            }
        } catch (Exception e) {
            log.warn("Failed to upload image from URL: {}", imageUrl, e);
            return null;
        }
    }

    // 간단한 확장자 체크 헬퍼
    private boolean isVideoExtension(String ext) {
        return List.of("mp4", "mov", "avi", "wmv", "mkv").contains(ext.toLowerCase());
    }
}
