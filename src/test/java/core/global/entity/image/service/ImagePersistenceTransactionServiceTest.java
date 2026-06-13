package core.global.entity.image.service;

import core.global.entity.image.entity.Image;
import core.global.config.CustomUserDetails;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageType;
import core.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImagePersistenceTransactionServiceTest {

    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageStorageClient storageClient;
    @Mock
    private ImageOperationBatchService imageOperationBatchService;
    @Mock
    private ImageUploadSessionService uploadSessionService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ImagePersistenceTransactionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bucket", "bucket");
        ReflectionTestUtils.setField(service, "endPoint", "https://object.example.com");
        ReflectionTestUtils.setField(service, "cdnBaseUrl", "https://cdn.example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomUserDetails(10L, "user@example.com", List.of(new SimpleGrantedAuthority("USER"))),
                        "password"
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loadSnapshotExcludesRemovedImagesWithoutChangingDatabase() {
        Image survivor = Image.of(ImageType.POST, 10L, "https://cdn.example.com/posts/10/a.jpg", 0);
        Image removed = Image.of(ImageType.POST, 10L, "https://cdn.example.com/posts/10/b.jpg", 1);
        when(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, 10L))
                .thenReturn(List.of(survivor, removed));
        when(storageClient.isDefaultUrlOrKey("posts/10/b.jpg")).thenReturn(false);

        ImagePersistenceTransactionService.PersistenceSnapshot snapshot = service.loadSnapshot(
                10L,
                List.of("https://cdn.example.com/posts/10/b.jpg")
        );

        assertThat(snapshot.survivorUrls())
                .containsExactly("https://cdn.example.com/posts/10/a.jpg");
        assertThat(snapshot.nextPosition()).isEqualTo(1);
        assertThat(snapshot.cleanupKeys()).containsExactly("posts/10/b.jpg");
    }

    @Test
    void persistWritesImagesAndCleanupWorkTogether() {
        Image existing = Image.of(ImageType.POST, 10L, "https://cdn.example.com/posts/10/a.jpg", 3);
        Image added = Image.of(ImageType.POST, 10L, "https://cdn.example.com/posts/10/b.jpg", 1);
        ImageOperationBatchService.TrackedCopy trackedCopy = new ImageOperationBatchService.TrackedCopy(
                UUID.randomUUID(),
                "temp/b.jpg",
                "posts/10/b.jpg"
        );
        ImagePersistenceTransactionService.PersistenceSnapshot snapshot =
                new ImagePersistenceTransactionService.PersistenceSnapshot(
                        List.of("https://cdn.example.com/posts/10/old.jpg"),
                        List.of("posts/10/old.jpg"),
                        java.util.Set.of("https://cdn.example.com/posts/10/a.jpg"),
                        1
                );
        when(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, 10L))
                .thenReturn(List.of(existing));
        when(imageRepository.saveAll(anyList())).thenReturn(List.of(added));

        service.persist(
                ImageOperationOwnerType.POST,
                10L,
                snapshot,
                List.of(added),
                List.of(trackedCopy)
        );

        assertThat(existing.getOrderIndex()).isZero();
        verify(imageRepository).deleteByImageTypeAndRelatedIdAndUrlIn(
                ImageType.POST,
                10L,
                snapshot.removeUrls()
        );
        verify(imageRepository).flush();
        verify(imageOperationBatchService).registerRollbackCompensation(List.of(trackedCopy));
        verify(imageOperationBatchService).scheduleCleanup(
                ImageOperationOwnerType.POST,
                10L,
                List.of(trackedCopy),
                List.of("posts/10/old.jpg")
        );
    }

    @Test
    void persistRejectsStaleSnapshotBeforeChangingDatabase() {
        Image concurrentImage = Image.of(
                ImageType.POST,
                10L,
                "https://cdn.example.com/posts/10/concurrent.jpg",
                0
        );
        when(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, 10L))
                .thenReturn(List.of(concurrentImage));

        assertThatThrownBy(() -> service.persist(
                ImageOperationOwnerType.POST,
                10L,
                ImagePersistenceTransactionService.PersistenceSnapshot.empty(),
                List.of(Image.of(ImageType.POST, 10L, "https://cdn.example.com/posts/10/new.jpg", 0)),
                List.of()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Image persistence snapshot is stale");
    }

    @Test
    void saveFinalPostImagesRegistersUuidFinalKeysWithoutCopyOperation() {
        Image saved = Image.of(
                ImageType.POST,
                10L,
                "https://cdn.example.com/posts/objects/new.jpg",
                0
        );
        when(imageRepository.existsByImageTypeAndRelatedId(ImageType.POST, 10L)).thenReturn(false);
        when(imageRepository.saveAll(anyList())).thenReturn(List.of(saved));

        service.saveFinalPostImages(10L, List.of("posts/objects/new.jpg"));

        verify(imageRepository).saveAll(anyList());
        verify(imageRepository).flush();
        verify(uploadSessionService).claimAndRegister(
                List.of("posts/objects/new.jpg"),
                10L
        );
        verify(imageOperationBatchService, never()).registerRollbackCompensation(anyList());
    }

    @Test
    void validateFinalPostImagesChecksEachDistinctObjectOutsidePersistence() {
        service.validateFinalPostImages(List.of(
                "posts/objects/a.jpg",
                "posts/objects/a.jpg",
                "https://cdn.example.com/posts/objects/b.jpg"
        ));

        verify(storageClient).headObject("posts/objects/a.jpg");
        verify(storageClient).headObject("posts/objects/b.jpg");
    }

    @Test
    void saveFinalPostImagesRejectsKeyAlreadyScheduledForCleanup() {
        when(imageRepository.existsByImageTypeAndRelatedId(ImageType.POST, 10L)).thenReturn(false);
        doThrow(new BusinessException(core.global.enums.errorcode.ImageErrorCode.IMAGE_UPLOAD_FAILED))
                .when(uploadSessionService)
                .claimAndRegister(List.of("posts/objects/expired.jpg"), 10L);

        assertThatThrownBy(() -> service.saveFinalPostImages(
                10L,
                List.of("posts/objects/expired.jpg")
        )).isInstanceOf(BusinessException.class);

        verify(imageRepository, never()).saveAll(anyList());
    }

    @Test
    void updateFinalPostImagesUpdatesDatabaseAndSchedulesOldObjectCleanup() {
        Image oldImage = Image.of(
                ImageType.POST,
                10L,
                "https://cdn.example.com/posts/10/old.jpg",
                0
        );
        Image newImage = Image.of(
                ImageType.POST,
                10L,
                "https://cdn.example.com/posts/objects/new.jpg",
                0
        );
        when(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, 10L))
                .thenReturn(List.of(oldImage));
        when(imageRepository.saveAll(anyList())).thenReturn(List.of(newImage));

        service.updateFinalPostImages(
                10L,
                List.of("posts/objects/new.jpg"),
                List.of("posts/10/old.jpg")
        );

        verify(imageRepository).deleteByImageTypeAndRelatedIdAndUrlIn(
                ImageType.POST,
                10L,
                java.util.Set.of("https://cdn.example.com/posts/10/old.jpg")
        );
        verify(imageOperationBatchService).scheduleCleanup(
                ImageOperationOwnerType.POST,
                10L,
                List.of(),
                List.of("posts/10/old.jpg")
        );
    }

    @Test
    void deletePostImagesSchedulesRegisteredObjectsInsteadOfPostFolder() {
        Image image = Image.of(
                ImageType.POST,
                10L,
                "https://cdn.example.com/posts/objects/existing.jpg",
                0
        );
        when(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, 10L))
                .thenReturn(List.of(image));

        service.deletePostImages(10L);

        verify(imageRepository).deleteByImageTypeAndRelatedId(ImageType.POST, 10L);
        verify(imageOperationBatchService).scheduleCleanup(
                ImageOperationOwnerType.POST,
                10L,
                List.of(),
                List.of("posts/objects/existing.jpg")
        );
    }
}
