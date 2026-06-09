package core.global.entity.image.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectResult;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ImageCopyExecutorTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private CopyObjectResponse response;
    @Mock
    private CopyObjectResult copyObjectResult;

    @InjectMocks
    private ImageCopyExecutor executor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(executor, "bucket", "test-bucket");
    }

    @Test
    @DisplayName("CopyObject 응답이 성공이고 ETag가 있으면 추가 HEAD 없이 성공한다")
    void copy_returnsResultWithoutHeadRequest() {
        when(response.sdkHttpResponse())
                .thenReturn(SdkHttpResponse.builder().statusCode(HttpStatus.OK.value()).build());
        when(response.copyObjectResult()).thenReturn(copyObjectResult);
        when(copyObjectResult.eTag()).thenReturn("\"result-etag\"");
        when(s3Client.copyObject(any(Consumer.class))).thenReturn(response);

        ImageCopyExecutor.ImageCopyResult result =
                executor.copy("temp/session/a.jpg", "posts/1/a.jpg");

        assertThat(result.targetKey()).isEqualTo("posts/1/a.jpg");
        assertThat(result.resultETag()).isEqualTo("\"result-etag\"");
        verify(s3Client).copyObject(any(Consumer.class));
        verifyNoMoreInteractions(s3Client);
    }

    @Test
    @DisplayName("CopyObject 성공 응답에 ETag가 없으면 실패 처리한다")
    void copy_rejectsResponseWithoutETag() {
        when(response.sdkHttpResponse())
                .thenReturn(SdkHttpResponse.builder().statusCode(HttpStatus.OK.value()).build());
        when(response.copyObjectResult()).thenReturn(copyObjectResult);
        when(copyObjectResult.eTag()).thenReturn(null);
        when(s3Client.copyObject(any(Consumer.class))).thenReturn(response);

        assertThatThrownBy(() -> executor.copy("temp/session/a.jpg", "posts/1/a.jpg"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("프로필 Copy는 기존 cache-control 정책을 유지한다")
    void copyProfile_preservesProfileMetadataPolicy() {
        when(response.sdkHttpResponse())
                .thenReturn(SdkHttpResponse.builder().statusCode(HttpStatus.OK.value()).build());
        when(response.copyObjectResult()).thenReturn(copyObjectResult);
        when(copyObjectResult.eTag()).thenReturn("\"result-etag\"");
        when(s3Client.copyObject(any(Consumer.class))).thenReturn(response);

        executor.copyProfile("temp/profile.jpg", "users/10/profile.jpg");

        ArgumentCaptor<Consumer<CopyObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).copyObject(captor.capture());
        CopyObjectRequest.Builder builder = CopyObjectRequest.builder();
        captor.getValue().accept(builder);
        CopyObjectRequest request = builder.build();
        assertThat(request.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
        assertThat(request.cacheControl()).isEqualTo("public, max-age=31536000, immutable");
    }
}
