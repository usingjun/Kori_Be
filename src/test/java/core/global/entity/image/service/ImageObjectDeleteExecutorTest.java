package core.global.entity.image.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageObjectDeleteExecutorTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private ImageStorageClient storageClient;

    @InjectMocks
    private ImageObjectDeleteExecutor executor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(executor, "bucket", "bucket");
    }

    @Test
    void deleteObject_deletesNonDefaultObject() {
        DeleteObjectResponse response = mock(DeleteObjectResponse.class);
        when(storageClient.isDefaultUrlOrKey("users/10/profile.jpg")).thenReturn(false);
        when(s3Client.deleteObject(any(java.util.function.Consumer.class))).thenReturn(response);
        when(response.sdkHttpResponse()).thenReturn(
                software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(204).build()
        );

        executor.deleteObject("users/10/profile.jpg");

        verify(s3Client).deleteObject(any(java.util.function.Consumer.class));
    }

    @Test
    void deleteObject_skipsDefaultObject() {
        when(storageClient.isDefaultUrlOrKey("default/profile.jpg")).thenReturn(true);

        executor.deleteObject("default/profile.jpg");

        verifyNoInteractions(s3Client);
    }
}
