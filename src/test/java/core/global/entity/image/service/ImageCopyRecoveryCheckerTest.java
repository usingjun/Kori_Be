package core.global.entity.image.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.function.Consumer;

import static core.global.entity.image.service.ImageCopyRecoveryChecker.CopyRecoveryResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageCopyRecoveryCheckerTest {

    @Mock
    private S3Client s3Client;

    @InjectMocks
    private ImageCopyRecoveryChecker checker;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(checker, "bucket", "test-bucket");
    }

    @Test
    @DisplayName("destination 크기와 ETag가 일치하면 이전 Copy 완료로 판단한다")
    void check_returnsAlreadyCompletedWhenMetadataMatches() {
        when(s3Client.headObject(any(Consumer.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(1024L).eTag("\"result-etag\"").build()
        );

        assertThat(checker.check("posts/1/a.jpg", 1024L, "\"result-etag\""))
                .isEqualTo(COPY_ALREADY_COMPLETED);
    }

    @Test
    @DisplayName("destination이 없으면 Copy가 필요하다고 판단한다")
    void check_returnsCopyRequiredWhenDestinationDoesNotExist() {
        when(s3Client.headObject(any(Consumer.class))).thenThrow(
                S3Exception.builder().statusCode(404).message("not found").build()
        );

        assertThat(checker.check("posts/1/a.jpg", 1024L, null))
                .isEqualTo(COPY_REQUIRED);
    }

    @Test
    @DisplayName("destination metadata가 다르면 충돌로 판단한다")
    void check_returnsConflictWhenMetadataDiffers() {
        when(s3Client.headObject(any(Consumer.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(2048L).eTag("\"other\"").build()
        );

        assertThat(checker.check("posts/1/a.jpg", 1024L, "\"result-etag\""))
                .isEqualTo(DESTINATION_CONFLICT);
    }

    @Test
    @DisplayName("검증 정보가 없으면 기존 destination을 성공으로 판단하지 않는다")
    void check_requiresExpectedMetadata() {
        assertThatThrownBy(() -> checker.check("posts/1/a.jpg", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
