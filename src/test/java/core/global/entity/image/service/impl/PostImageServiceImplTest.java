package core.global.entity.image.service.impl;

import core.global.entity.image.S3Props;
import core.global.config.CustomUserDetails;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.ImagePersistenceTransactionService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.ImageUploadSessionService;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URL;
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
    @Mock
    private ImagePersistenceTransactionService persistenceTransactionService;
    @Mock
    private ImageUploadSessionService uploadSessionService;

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

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generatePresignedUrls_issuesUploadSessionForPostFinalKey() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomUserDetails(10L, "owner@example.com", List.of(new SimpleGrantedAuthority("USER"))),
                        "password"
                )
        );
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://object.example.com/upload"));
        when(s3Presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenReturn(presigned);

        var result = postImageService.generatePresignedUrls(new PresignedUrlRequest(
                ImageType.POST,
                "session-id",
                List.of(new PresignedUrlRequest.FileSpec("photo.jpg", "image/jpeg"))
        ));

        verify(uploadSessionService).issue(
                eq(result.get(0).key()),
                eq(10L),
                eq(ImageType.POST)
        );
    }

    @Test
    void savePostImagesTracksCopyAndSchedulesStagingCleanup() {
        when(persistenceTransactionService.postImagesExist(10L)).thenReturn(false);
        when(imageOperationBatchService.copyAll(any(), any(), eq(10L), anyList()))
                .thenReturn(List.of(trackedCopy));

        postImageService.savePostImages(10L, List.of("temp/a.jpg"));

        verify(persistenceTransactionService).persist(
                eq(ImageOperationOwnerType.POST),
                eq(10L),
                eq(ImagePersistenceTransactionService.PersistenceSnapshot.empty()),
                anyList(),
                eq(List.of(trackedCopy))
        );
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    void updatePostImagesSchedulesRemovedObjectCleanupWithoutDirectDelete() {
        ImagePersistenceTransactionService.PersistenceSnapshot snapshot =
                new ImagePersistenceTransactionService.PersistenceSnapshot(
                        List.of("https://cdn.example.com/posts/10/old.jpg"),
                        List.of("posts/10/old.jpg"),
                        java.util.Set.of(),
                        0
                );
        when(persistenceTransactionService.loadSnapshot(anyLong(), anyList())).thenReturn(snapshot);

        postImageService.updatePostImages(
                10L,
                List.of(),
                List.of("https://cdn.example.com/posts/10/old.jpg")
        );

        verify(persistenceTransactionService).persist(
                ImageOperationOwnerType.POST, 10L, snapshot, List.of(), List.of()
        );
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    void savePostImagesCompensatesWhenDbFlushFails() {
        when(persistenceTransactionService.postImagesExist(10L)).thenReturn(false);
        when(imageOperationBatchService.copyAll(any(), any(), eq(10L), anyList()))
                .thenReturn(List.of(trackedCopy));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(persistenceTransactionService)
                .persist(any(), anyLong(), any(), anyList(), anyList());

        assertThatThrownBy(() -> postImageService.savePostImages(10L, List.of("temp/a.jpg")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(imageOperationBatchService).compensate(List.of(trackedCopy));
        verify(persistenceTransactionService).persist(any(), anyLong(), any(), anyList(), anyList());
    }

    @Test
    void savePostImagesCompensatesSuccessfulCopiesWhenAnotherCopyFails() {
        lenient().when(storageClient.isStagingKey("temp/b.jpg")).thenReturn(true);
        when(persistenceTransactionService.postImagesExist(10L)).thenReturn(false);
        when(imageOperationBatchService.copyAll(any(), any(), eq(10L), anyList()))
                .thenThrow(new IllegalStateException("copy failed"));

        assertThatThrownBy(() -> postImageService.savePostImages(10L, List.of("temp/a.jpg", "temp/b.jpg")))
                .isInstanceOf(core.global.exception.BusinessException.class);

        verify(persistenceTransactionService, never()).persist(any(), anyLong(), any(), anyList(), anyList());
    }

}
