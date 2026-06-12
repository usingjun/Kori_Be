package core.global.entity.image.service.impl;

import core.global.entity.image.S3Props;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.enums.common.ImageOperationOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostImageServiceImplTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageStorageClient storageClient;
    @Mock
    private S3Presigner s3Presigner;
    @Mock
    private S3Props s3Props;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ImageOperationBatchService imageOperationBatchService;

    @InjectMocks
    private PostImageServiceImpl postImageService;

    private ImageOperationBatchService.TrackedCopy trackedCopy;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(postImageService, "bucket", "bucket");
        ReflectionTestUtils.setField(postImageService, "endPoint", "https://object.example.com");
        ReflectionTestUtils.setField(postImageService, "cdnBaseUrl", "https://cdn.example.com");
        lenient().when(storageClient.isStagingKey("temp/a.jpg")).thenReturn(true);
        trackedCopy = new ImageOperationBatchService.TrackedCopy(
                UUID.randomUUID(), "temp/a.jpg", "posts/10/000_a.jpg"
        );
    }

    @Test
    void savePostImagesTracksCopyAndSchedulesStagingCleanup() {
        when(imageRepository.existsByImageTypeAndRelatedId(any(), eq(10L))).thenReturn(false);
        when(imageOperationBatchService.copyAll(any(), any(), eq(10L), anyList()))
                .thenReturn(List.of(trackedCopy));
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        postImageService.savePostImages(10L, List.of("temp/a.jpg"));

        verify(imageOperationBatchService).registerRollbackCompensation(List.of(trackedCopy));
        verify(imageRepository).flush();
        verify(imageOperationBatchService).scheduleCleanup(
                ImageOperationOwnerType.POST, 10L, List.of(trackedCopy), List.of()
        );
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    void updatePostImagesSchedulesRemovedObjectCleanupWithoutDirectDelete() {
        postImageService.updatePostImages(
                10L,
                List.of(),
                List.of("https://cdn.example.com/posts/10/old.jpg")
        );

        verify(imageOperationBatchService).scheduleCleanup(
                ImageOperationOwnerType.POST,
                10L,
                List.of(),
                List.of("posts/10/old.jpg")
        );
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    void savePostImagesCompensatesWhenDbFlushFails() {
        when(imageRepository.existsByImageTypeAndRelatedId(any(), eq(10L))).thenReturn(false);
        when(imageOperationBatchService.copyAll(any(), any(), eq(10L), anyList()))
                .thenReturn(List.of(trackedCopy));
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataAccessResourceFailureException("db down")).when(imageRepository).flush();

        assertThatThrownBy(() -> postImageService.savePostImages(10L, List.of("temp/a.jpg")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(imageOperationBatchService).compensate(List.of(trackedCopy));
        verify(imageOperationBatchService, never()).scheduleCleanup(any(), anyLong(), anyList(), anyList());
    }

    @Test
    void savePostImagesCompensatesSuccessfulCopiesWhenAnotherCopyFails() {
        lenient().when(storageClient.isStagingKey("temp/b.jpg")).thenReturn(true);
        when(imageRepository.existsByImageTypeAndRelatedId(any(), eq(10L))).thenReturn(false);
        when(imageOperationBatchService.copyAll(any(), any(), eq(10L), anyList()))
                .thenThrow(new IllegalStateException("copy failed"));

        assertThatThrownBy(() -> postImageService.savePostImages(10L, List.of("temp/a.jpg", "temp/b.jpg")))
                .isInstanceOf(core.global.exception.BusinessException.class);

        verify(imageRepository, never()).saveAll(anyList());
    }

}
