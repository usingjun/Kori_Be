package core.global.entity.image.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("performance")
class NcpObjectStorageConnectionVerificationTest {

    private static final String VERIFICATION_KEY =
            "temp/codex-post-benchmark/manual-verification-8mb.jpg";
    private static final String LEGACY_VERIFICATION_KEY =
            "temp/codex-post-benchmark/manual-verification.txt";

    @Test
    void uploadOrDeleteVerificationObject() {
        String action = System.getenv("NCP_OBJECT_STORAGE_VERIFY_ACTION");
        assumeTrue(action != null && !action.isBlank(),
                "Run with NCP_OBJECT_STORAGE_VERIFY_ACTION=UPLOAD or DELETE");

        try (S3Client s3Client = createClient()) {
            String bucket = requiredEnv("NCP_BUCKET_NAME");
            if ("UPLOAD".equalsIgnoreCase(action)) {
                uploadAndVerify(s3Client, bucket);
                return;
            }
            if ("DELETE".equalsIgnoreCase(action)) {
                delete(s3Client, bucket);
                return;
            }
            throw new IllegalArgumentException(
                    "NCP_OBJECT_STORAGE_VERIFY_ACTION must be UPLOAD or DELETE"
            );
        }
    }

    private void uploadAndVerify(S3Client s3Client, String bucket) {
        byte[] content = BenchmarkJpegFactory.createEightMiBJpeg();
        s3Client.putObject(
                request -> request.bucket(bucket).key(VERIFICATION_KEY).contentType("image/jpeg"),
                RequestBody.fromBytes(content)
        );

        var head = s3Client.headObject(request -> request.bucket(bucket).key(VERIFICATION_KEY));
        assertThat(head.contentLength()).isEqualTo(content.length);
        System.out.printf(
                "verificationAction=UPLOAD,bucket=%s,key=%s,contentLength=%d%n",
                bucket,
                VERIFICATION_KEY,
                head.contentLength()
        );
    }

    private void delete(S3Client s3Client, String bucket) {
        s3Client.deleteObject(request -> request.bucket(bucket).key(VERIFICATION_KEY));
        s3Client.deleteObject(request -> request.bucket(bucket).key(LEGACY_VERIFICATION_KEY));
        System.out.printf(
                "verificationAction=DELETE,bucket=%s,key=%s%n",
                bucket,
                VERIFICATION_KEY
        );
    }

    private S3Client createClient() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                requiredEnv("NCP_ACCESS_KEY"),
                requiredEnv("NCP_SECRET_KEY")
        );
        return S3Client.builder()
                .region(Region.of("kr-standard"))
                .endpointOverride(URI.create("https://kr.object.ncloudstorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
