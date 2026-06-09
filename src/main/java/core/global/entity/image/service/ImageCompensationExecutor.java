package core.global.entity.image.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

@Component
@RequiredArgsConstructor
public class ImageCompensationExecutor {

    private final S3Client s3Client;
    private final ImageStorageClient storageClient;

    @Value("${ncp.s3.bucket}")
    private String bucket;

    public void deleteFinalObject(String targetKey) {
        if (storageClient.isDefaultUrlOrKey(targetKey)) {
            return;
        }
        DeleteObjectResponse response = s3Client.deleteObject(b -> b.bucket(bucket).key(targetKey));
        if (response == null
                || response.sdkHttpResponse() == null
                || !response.sdkHttpResponse().isSuccessful()) {
            throw new IllegalStateException("Object Storage delete response did not confirm success");
        }
    }
}
