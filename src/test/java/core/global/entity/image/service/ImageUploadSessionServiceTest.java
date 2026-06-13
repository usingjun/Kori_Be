package core.global.entity.image.service;

import core.global.entity.image.entity.ImageUploadSession;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.repository.ImageUploadSessionRepository;
import core.global.enums.common.ImageType;
import core.global.enums.common.ImageUploadSessionStatus;
import core.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageUploadSessionServiceTest {

    @Mock
    private ImageUploadSessionRepository sessionRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageStorageClient storageClient;
    @Mock
    private ImageOperationRecoveryService recoveryService;

    @InjectMocks
    private ImageUploadSessionService service;

    @Test
    void issue_savesIssuedSessionWithRetention() {
        ReflectionTestUtils.setField(service, "retention", Duration.ofHours(24));

        service.issue("posts/objects/a.jpg", 10L, ImageType.POST);

        ArgumentCaptor<ImageUploadSession> captor = ArgumentCaptor.forClass(ImageUploadSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ImageUploadSessionStatus.ISSUED);
        assertThat(captor.getValue().getObjectKey()).isEqualTo("posts/objects/a.jpg");
        assertThat(captor.getValue().getOwnerId()).isEqualTo(10L);
    }

    @Test
    void claimAndRegister_registersOwnedSessions() {
        ImageUploadSession session = issued("posts/objects/a.jpg", 10L);
        when(sessionRepository.findByObjectKeyInForUpdate(List.of("posts/objects/a.jpg")))
                .thenReturn(List.of(session));

        service.claimAndRegister(List.of("posts/objects/a.jpg"), 10L);

        assertThat(session.getStatus()).isEqualTo(ImageUploadSessionStatus.REGISTERED);
    }

    @Test
    void claimAndRegister_rejectsDifferentOwner() {
        ImageUploadSession session = issued("posts/objects/a.jpg", 10L);
        when(sessionRepository.findByObjectKeyInForUpdate(List.of("posts/objects/a.jpg")))
                .thenReturn(List.of(session));

        assertThatThrownBy(() -> service.claimAndRegister(
                List.of("posts/objects/a.jpg"),
                11L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void registerExistingSessions_registersPollSessionWithoutRejectingLegacyKeys() {
        ImageUploadSession session = issued("posts/objects/poll.jpg", 10L);
        when(sessionRepository.findByObjectKeyInForUpdate(List.of(
                "posts/objects/poll.jpg",
                "vote/10/legacy.jpg"
        ))).thenReturn(List.of(session));

        service.registerExistingSessions(List.of(
                "posts/objects/poll.jpg",
                "vote/10/legacy.jpg"
        ));

        assertThat(session.getStatus()).isEqualTo(ImageUploadSessionStatus.REGISTERED);
    }

    @Test
    void registerExistingSessions_rejectsDeletePendingSession() {
        ImageUploadSession session = issued("posts/objects/poll.jpg", 10L);
        session.markDeletePending();
        when(sessionRepository.findByObjectKeyInForUpdate(List.of("posts/objects/poll.jpg")))
                .thenReturn(List.of(session));

        assertThatThrownBy(() -> service.registerExistingSessions(List.of("posts/objects/poll.jpg")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void scheduleExpiredSessions_registersUsedObjectsAndSchedulesUnusedObjects() {
        ImageUploadSession used = issued("posts/objects/used.jpg", 10L);
        ImageUploadSession unused = issued("posts/objects/unused.jpg", 10L);
        when(sessionRepository.findTop200ByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(List.of(ImageUploadSessionStatus.ISSUED)),
                any(LocalDateTime.class)
        )).thenReturn(List.of(used, unused));
        when(storageClient.generatePublicUrl(anyString()))
                .thenAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));
        when(imageRepository.findRegisteredUrls(anyCollection()))
                .thenReturn(List.of("https://cdn.example.com/posts/objects/used.jpg"));
        when(recoveryService.scheduleUnregisteredPostObjectDeletes(List.of("posts/objects/unused.jpg")))
                .thenReturn(1);

        int scheduled = service.scheduleExpiredSessions();

        assertThat(scheduled).isEqualTo(1);
        assertThat(used.getStatus()).isEqualTo(ImageUploadSessionStatus.REGISTERED);
        assertThat(unused.getStatus()).isEqualTo(ImageUploadSessionStatus.DELETE_PENDING);
        verify(recoveryService).scheduleUnregisteredPostObjectDeletes(List.of("posts/objects/unused.jpg"));
    }

    @Test
    void completeDelete_marksPendingSessionDeleted() {
        ImageUploadSession session = issued("posts/objects/a.jpg", 10L);
        session.markDeletePending();
        when(sessionRepository.findByObjectKeyForUpdate("posts/objects/a.jpg"))
                .thenReturn(Optional.of(session));
        when(storageClient.generatePublicUrl("posts/objects/a.jpg"))
                .thenReturn("https://cdn.example.com/posts/objects/a.jpg");

        service.completeDelete("posts/objects/a.jpg");

        assertThat(session.getStatus()).isEqualTo(ImageUploadSessionStatus.DELETED);
    }

    @Test
    void retryFailedDeletes_reschedulesOldFailedSessions() {
        ReflectionTestUtils.setField(service, "deleteFailedRetryDelay", Duration.ofHours(1));
        ImageUploadSession session = issued("posts/objects/a.jpg", 10L);
        session.markDeletePending();
        session.markDeleteFailed();
        when(sessionRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(ImageUploadSessionStatus.DELETE_FAILED),
                any(LocalDateTime.class)
        )).thenReturn(List.of(session));
        when(recoveryService.scheduleUnregisteredPostObjectDeletes(List.of("posts/objects/a.jpg")))
                .thenReturn(1);

        int scheduled = service.retryFailedDeletes();

        assertThat(scheduled).isEqualTo(1);
        assertThat(session.getStatus()).isEqualTo(ImageUploadSessionStatus.DELETE_PENDING);
    }

    @Test
    void purgeTerminalSessions_removesOnlyExpiredRegisteredAndDeletedSessions() {
        ReflectionTestUtils.setField(service, "registeredRetention", Duration.ofDays(90));
        ReflectionTestUtils.setField(service, "deletedRetention", Duration.ofDays(30));
        when(sessionRepository.deleteTerminalSessions(
                eq(ImageUploadSessionStatus.DELETED),
                any(LocalDateTime.class)
        )).thenReturn(2);
        when(sessionRepository.deleteTerminalSessions(
                eq(ImageUploadSessionStatus.REGISTERED),
                any(LocalDateTime.class)
        )).thenReturn(3);

        assertThat(service.purgeTerminalSessions()).isEqualTo(5L);
        verify(sessionRepository, never()).deleteTerminalSessions(
                eq(ImageUploadSessionStatus.DELETE_FAILED),
                any(LocalDateTime.class)
        );
    }

    private ImageUploadSession issued(String objectKey, Long ownerId) {
        return ImageUploadSession.issue(
                objectKey,
                ownerId,
                ImageType.POST,
                LocalDateTime.now().plusHours(24)
        );
    }

}
