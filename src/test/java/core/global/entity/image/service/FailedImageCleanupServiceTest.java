package core.global.entity.image.service;

import core.global.entity.image.entity.FailedImageCleanup;
import core.global.entity.image.repository.FailedImageCleanupRepository;
import core.global.enums.common.ImageCleanupOperationType;
import core.global.enums.common.ImageCleanupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FailedImageCleanupServiceTest {

    @Mock
    private FailedImageCleanupRepository failedImageCleanupRepository;
    @Mock
    private S3Client s3Client;
    @Mock
    private ImageCleanupOperationBridge imageCleanupOperationBridge;
    @Mock
    private ImageCleanupOperationService imageCleanupOperationService;

    @InjectMocks
    private FailedImageCleanupService failedImageCleanupService;

    @Test
    @DisplayName("DELETE_OBJECT 실패 기록 - 기존 작업이 없으면 새 cleanup 작업을 저장한다")
    void recordDeleteObject_createsNewCleanupWhenAbsent() {
        when(failedImageCleanupRepository.findByOperationTypeAndTargetKey(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        )).thenReturn(Optional.empty());
        when(failedImageCleanupRepository.save(any(FailedImageCleanup.class))).thenAnswer(invocation -> {
            FailedImageCleanup cleanup = invocation.getArgument(0);
            ReflectionTestUtils.setField(cleanup, "id", 1L);
            return cleanup;
        });

        failedImageCleanupService.recordDeleteObject("/posts/1/a.jpg", "timeout");

        ArgumentCaptor<FailedImageCleanup> captor = ArgumentCaptor.forClass(FailedImageCleanup.class);
        verify(failedImageCleanupRepository).save(captor.capture());

        FailedImageCleanup saved = captor.getValue();
        assertThat(saved.getOperationType()).isEqualTo(ImageCleanupOperationType.DELETE_OBJECT);
        assertThat(saved.getTargetKey()).isEqualTo("posts/1/a.jpg");
        assertThat(saved.getStatus()).isEqualTo(ImageCleanupStatus.PENDING);
        assertThat(saved.getAttemptCount()).isZero();
        assertThat(saved.getLastError()).isEqualTo("timeout");
        assertThat(saved.getNextRetryAt()).isNotNull();
        verify(imageCleanupOperationBridge).recordCommonOutbox(
                1L,
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );
    }

    @Test
    @DisplayName("DELETE_OBJECT 실패 기록 - 기존 작업이 있으면 중복 저장하지 않고 기존 작업을 갱신한다")
    void recordDeleteObject_refreshesExistingCleanup() {
        FailedImageCleanup existing = FailedImageCleanup.create(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg",
                "old error"
        );
        existing.markSuccess();
        ReflectionTestUtils.setField(existing, "id", 2L);

        when(failedImageCleanupRepository.findByOperationTypeAndTargetKey(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        )).thenReturn(Optional.of(existing));

        failedImageCleanupService.recordDeleteObject("posts/1/a.jpg", "new error");

        assertThat(existing.getStatus()).isEqualTo(ImageCleanupStatus.PENDING);
        assertThat(existing.getAttemptCount()).isZero();
        assertThat(existing.getLastError()).isEqualTo("new error");
        verify(failedImageCleanupRepository, never()).save(any());
        verify(imageCleanupOperationBridge).recordCommonOutbox(
                2L,
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );
    }

    @Test
    @DisplayName("실패 기록 - default/ key는 fallback과 RabbitMQ에 기록하지 않는다")
    void recordDeleteObject_ignoresDefaultKey() {
        failedImageCleanupService.recordDeleteObject("default/profile.png", "timeout");

        verifyNoInteractions(failedImageCleanupRepository, imageCleanupOperationBridge);
    }

    @Test
    @DisplayName("재처리 - DELETE_OBJECT 삭제에 성공하면 SUCCESS 상태로 변경한다")
    void retryDueCleanups_marksSuccessWhenDeleteObjectSucceeds() {
        ReflectionTestUtils.setField(failedImageCleanupService, "bucket", "test-bucket");
        FailedImageCleanup cleanup = FailedImageCleanup.create(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg",
                "timeout"
        );

        when(failedImageCleanupRepository.findRetryTargets(anyCollection(), any(), any(Pageable.class)))
                .thenReturn(List.of(cleanup));
        when(s3Client.deleteObject(any(Consumer.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        int processed = failedImageCleanupService.retryDueCleanups(10);

        assertThat(processed).isEqualTo(1);
        assertThat(cleanup.getStatus()).isEqualTo(ImageCleanupStatus.SUCCESS);
        assertThat(cleanup.getLastError()).isNull();
        verify(s3Client).deleteObject(any(Consumer.class));
        verify(imageCleanupOperationService).markFallbackCompleted(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg"
        );
    }

    @Test
    @DisplayName("재처리 - DELETE_OBJECT 삭제에 실패하면 FAILED 상태와 다음 재시도 시간을 기록한다")
    void retryDueCleanups_marksFailedWhenDeleteObjectFails() {
        ReflectionTestUtils.setField(failedImageCleanupService, "bucket", "test-bucket");
        FailedImageCleanup cleanup = FailedImageCleanup.create(
                ImageCleanupOperationType.DELETE_OBJECT,
                "posts/1/a.jpg",
                "timeout"
        );

        when(failedImageCleanupRepository.findRetryTargets(anyCollection(), any(), any(Pageable.class)))
                .thenReturn(List.of(cleanup));
        when(s3Client.deleteObject(any(Consumer.class)))
                .thenThrow(SdkException.builder().message("s3 down").build());

        int processed = failedImageCleanupService.retryDueCleanups(10);

        assertThat(processed).isZero();
        assertThat(cleanup.getStatus()).isEqualTo(ImageCleanupStatus.FAILED);
        assertThat(cleanup.getAttemptCount()).isEqualTo(1);
        assertThat(cleanup.getLastError()).isEqualTo("s3 down");
        assertThat(cleanup.getNextRetryAt()).isNotNull();
        verifyNoInteractions(imageCleanupOperationService);
    }
}
