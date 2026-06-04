package core.global.entity.image.service.impl;

import core.global.entity.image.S3Props;
import core.global.entity.image.service.FailedImageCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Error;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3ImageStorageClientTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private FailedImageCleanupService failedImageCleanupService;

    private S3ImageStorageClient s3ImageStorageClient;

    @BeforeEach
    void setUp() {
        s3ImageStorageClient = new S3ImageStorageClient(s3Client, new S3Props(), failedImageCleanupService);
        ReflectionTestUtils.setField(s3ImageStorageClient, "bucket", "test-bucket");
        ReflectionTestUtils.setField(s3ImageStorageClient, "endPoint", "https://kr.object.ncloudstorage.com");
        ReflectionTestUtils.setField(s3ImageStorageClient, "cdnBaseUrl", "https://cdn.example.com");
    }

    @Test
    @DisplayName("bulk delete 일부 실패 시 실패한 key만 cleanup 실패 작업으로 기록한다")
    void deleteObjectsBulk_recordsOnlyFailedKeysWhenPartialFailure() {
        when(s3Client.deleteObjects(any(Consumer.class))).thenReturn(DeleteObjectsResponse.builder()
                .errors(S3Error.builder()
                        .key("posts/1/fail.jpg")
                        .code("AccessDenied")
                        .message("denied")
                        .build())
                .build());

        s3ImageStorageClient.deleteObjectsBulk(List.of("posts/1/ok.jpg", "posts/1/fail.jpg"));

        verify(failedImageCleanupService).recordDeleteObject("posts/1/fail.jpg", "denied");
        verify(failedImageCleanupService, never()).recordDeleteObject(eq("posts/1/ok.jpg"), any());
    }

    @Test
    @DisplayName("bulk delete chunk 전체 실패 시 chunk의 모든 key를 cleanup 실패 작업으로 기록한다")
    void deleteObjectsBulk_recordsAllChunkKeysWhenChunkFails() {
        when(s3Client.deleteObjects(any(Consumer.class)))
                .thenThrow(SdkException.builder().message("s3 down").build());

        s3ImageStorageClient.deleteObjectsBulk(List.of("posts/1/a.jpg", "posts/1/b.jpg"));

        verify(failedImageCleanupService).recordDeleteObject("posts/1/a.jpg", "s3 down");
        verify(failedImageCleanupService).recordDeleteObject("posts/1/b.jpg", "s3 down");
    }

    @Test
    @DisplayName("bulk delete 실패 시 default/ key는 삭제 요청과 cleanup 실패 기록에서 제외한다")
    void deleteObjectsBulk_ignoresDefaultKeysWhenChunkFails() {
        when(s3Client.deleteObjects(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<DeleteObjectsRequest.Builder> consumer = invocation.getArgument(0);
            DeleteObjectsRequest.Builder builder = DeleteObjectsRequest.builder();
            consumer.accept(builder);
            DeleteObjectsRequest request = builder.build();

            if (request.delete().objects().stream().anyMatch(object -> object.key().startsWith("default/"))) {
                throw new AssertionError("default/ key must not be sent to bulk delete");
            }
            throw SdkException.builder().message("s3 down").build();
        });

        s3ImageStorageClient.deleteObjectsBulk(List.of("default/profile.png", "posts/1/a.jpg"));

        verify(failedImageCleanupService).recordDeleteObject("posts/1/a.jpg", "s3 down");
        verify(failedImageCleanupService, never()).recordDeleteObject(startsWith("default/"), any());
    }
}
