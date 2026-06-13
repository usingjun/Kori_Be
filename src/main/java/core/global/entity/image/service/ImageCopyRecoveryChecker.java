package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class ImageCopyRecoveryChecker {

    private final S3Client s3Client;

    @Value("${ncp.s3.bucket}")
    private String bucket;

    public CopyRecoveryResult check(
            String targetKey,
            Long expectedContentLength,
            String expectedResultETag
    ) {
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("targetKey must not be blank");
        }
        if (expectedContentLength == null && (expectedResultETag == null || expectedResultETag.isBlank())) {
            throw new IllegalArgumentException("Copy recovery requires content length or result ETag");
        }
        try {
            HeadObjectResponse response = s3Client.headObject(b -> b.bucket(bucket).key(targetKey));
            if (!matchesContentLength(response, expectedContentLength)
                    || !matchesETag(response, expectedResultETag)) {
                return CopyRecoveryResult.DESTINATION_CONFLICT;
            }
            return CopyRecoveryResult.COPY_ALREADY_COMPLETED;
        } catch (NoSuchKeyException e) {
            return CopyRecoveryResult.COPY_REQUIRED;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return CopyRecoveryResult.COPY_REQUIRED;
            }
            throw e;
        }
    }

    private boolean matchesContentLength(HeadObjectResponse response, Long expectedContentLength) {
        return expectedContentLength == null || expectedContentLength.equals(response.contentLength());
    }

    private boolean matchesETag(HeadObjectResponse response, String expectedResultETag) {
        return expectedResultETag == null
                || expectedResultETag.isBlank()
                || expectedResultETag.equals(response.eTag());
    }

    public enum CopyRecoveryResult {
        COPY_ALREADY_COMPLETED,
        COPY_REQUIRED,
        DESTINATION_CONFLICT
    }
}
