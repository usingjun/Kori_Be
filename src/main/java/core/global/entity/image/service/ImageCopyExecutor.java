package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

@Component
@RequiredArgsConstructor
public class ImageCopyExecutor {

    private final S3Client s3Client;

    @Value("${ncp.s3.bucket}")
    private String bucket;

    public ImageCopyResult copy(String sourceKey, String targetKey) {
        CopyObjectResponse response = s3Client.copyObject(b -> b
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(targetKey)
                .acl(ObjectCannedACL.PUBLIC_READ)
                .metadataDirective(MetadataDirective.COPY)
        );

        if (response == null
                || response.sdkHttpResponse() == null
                || !response.sdkHttpResponse().isSuccessful()
                || response.copyObjectResult() == null
                || response.copyObjectResult().eTag() == null
                || response.copyObjectResult().eTag().isBlank()) {
            throw new IllegalStateException("Object Storage copy response did not confirm success");
        }

        return new ImageCopyResult(targetKey, response.copyObjectResult().eTag());
    }

    public record ImageCopyResult(String targetKey, String resultETag) {
    }
}
