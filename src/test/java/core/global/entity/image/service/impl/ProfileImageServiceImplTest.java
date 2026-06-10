package core.global.entity.image.service.impl;

import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.*;
import core.global.enums.ImageModerationStatus;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import core.global.enums.common.ImageType;
import core.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileImageServiceImplTest {

    private static final Long USER_ID = 10L;
    private static final String SOURCE_KEY = "temp/user/profile.jpg";
    private static final String TARGET_KEY = "users/10/profile.fixed.jpg";
    private static final String OLD_KEY = "users/10/profile.old.jpg";
    private static final Long CHAT_ROOM_ID = 20L;
    private static final String CHAT_SOURCE_KEY = "temp/chat/profile.jpg";
    private static final String CHAT_TARGET_KEY = "chatRoom/20/chat_profile.fixed.jpg";
    private static final String CHAT_OLD_KEY = "chatRoom/20/chat_profile.old.jpg";
    private static final String CDN_BASE_URL = "https://cdn.example.com";

    @Mock
    private S3Client s3Client;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageStorageClient storageClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ImageOperationService imageOperationService;
    @Mock
    private ImageOperationStepService imageOperationStepService;
    @Mock
    private ImageOperationRecoveryService imageOperationRecoveryService;
    @Mock
    private ImageCopyExecutor imageCopyExecutor;

    @InjectMocks
    private ProfileImageServiceImpl profileImageService;

    private UUID operationId;
    private UUID stepId;
    private Image existingImage;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(profileImageService, "bucket", "test-bucket");
        ReflectionTestUtils.setField(profileImageService, "endPoint", "https://object.example.com");
        ReflectionTestUtils.setField(profileImageService, "cdnBaseUrl", CDN_BASE_URL);
        ReflectionTestUtils.setField(profileImageService, "imageOperationRabbitEnabled", true);

        operationId = UUID.randomUUID();
        stepId = UUID.randomUUID();
        existingImage = Image.of(
                ImageType.USER,
                USER_ID,
                CDN_BASE_URL + "/" + OLD_KEY,
                0,
                ImageModerationStatus.CLEAN,
                null
        );
    }

    @Test
    @DisplayName("프로필 staging Copy와 DB 반영 성공 시 기존 object와 staging cleanup을 예약한다")
    void updateUserProfileImage_schedulesCleanupInTransaction() {
        prepareStagingUpdate();
        TransactionSynchronizationManager.initSynchronization();
        try {
            String result = profileImageService.updateUserProfileImage(USER_ID, SOURCE_KEY);

            assertThat(result).isEqualTo(CDN_BASE_URL + "/" + TARGET_KEY);
            assertThat(existingImage.getUrl()).isEqualTo(result);
            verify(imageRepository).flush();
            verify(storageClient, never()).deleteObjectsBulk(anyList());
            verify(imageOperationRecoveryService).scheduleDeleteObjects(
                    operationId,
                    ImageOperationOwnerType.USER,
                    USER_ID,
                    List.of(OLD_KEY, SOURCE_KEY)
            );
            verify(imageOperationService, never()).markCompleted(operationId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("프로필 Copy 실패 시 기존 DB 이미지와 object를 유지한다")
    void updateUserProfileImage_keepsExistingImageWhenCopyFails() {
        prepareStagingUpdate();
        reset(imageCopyExecutor);
        doThrow(new IllegalStateException("copy failed"))
                .when(imageCopyExecutor).copyProfile(SOURCE_KEY, TARGET_KEY);
        assertThatThrownBy(() -> profileImageService.updateUserProfileImage(USER_ID, SOURCE_KEY))
                .isInstanceOf(BusinessException.class);

        assertThat(existingImage.getUrl()).isEqualTo(CDN_BASE_URL + "/" + OLD_KEY);
        verify(imageOperationStepService).markTerminalFailed(stepId, "copy failed");
        verify(imageOperationService).markFailed(operationId);
        verify(imageRepository, never()).flush();
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    @DisplayName("RabbitMQ 비활성화 시 기존 afterCommit cleanup을 fallback으로 유지한다")
    void updateUserProfileImage_usesDirectCleanupFallbackWhenRabbitDisabled() {
        prepareStagingUpdate();
        ReflectionTestUtils.setField(profileImageService, "imageOperationRabbitEnabled", false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            profileImageService.updateUserProfileImage(USER_ID, SOURCE_KEY);

            verify(imageOperationRecoveryService, never()).scheduleDeleteObjects(any(), any(), any(), anyList());
            verify(storageClient, never()).deleteObjectsBulk(anyList());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(storageClient).deleteObjectsBulk(List.of(OLD_KEY, SOURCE_KEY));
            verify(imageOperationService).markCompleted(operationId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Copy 성공 후 DB flush 실패 시 final object 보상 step을 생성한다")
    void updateUserProfileImage_createsCompensationWhenDbFlushFails() {
        prepareStagingUpdate();
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(imageRepository).flush();

        assertThatThrownBy(() -> profileImageService.updateUserProfileImage(USER_ID, SOURCE_KEY))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(imageOperationRecoveryService).scheduleCompensation(operationId, TARGET_KEY);
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    @DisplayName("Copy와 DB flush 후 최종 transaction rollback 시 compensation step을 생성한다")
    void updateUserProfileImage_createsCompensationWhenTransactionRollsBack() {
        prepareStagingUpdate();
        TransactionSynchronizationManager.initSynchronization();
        try {
            profileImageService.updateUserProfileImage(USER_ID, SOURCE_KEY);

            verify(storageClient, never()).deleteObjectsBulk(anyList());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization ->
                            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(imageOperationRecoveryService).scheduleCompensation(operationId, TARGET_KEY);
            verify(imageOperationRecoveryService).scheduleDeleteObjects(
                    operationId,
                    ImageOperationOwnerType.USER,
                    USER_ID,
                    List.of(OLD_KEY, SOURCE_KEY)
            );
            verify(imageOperationService, never()).markCompleted(operationId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("채팅방 프로필 수정 성공 시 기존 이미지를 선삭제하지 않고 cleanup Outbox를 예약한다")
    void updateChatRoomProfileImage_schedulesCleanupWithoutPreDelete() {
        Image chatImage = prepareChatRoomStagingUpdate();
        prepareChatRoomCopySuccess();

        String result = profileImageService.updateChatRoomProfileImage(CHAT_ROOM_ID, CHAT_SOURCE_KEY);

        assertThat(result).isEqualTo(CDN_BASE_URL + "/" + CHAT_TARGET_KEY);
        assertThat(chatImage.getUrl()).isEqualTo(result);
        verify(imageRepository).flush();
        verify(imageRepository, never()).deleteByImageTypeAndRelatedIdWithFlushing(any(), any());
        verify(storageClient, never()).deleteObjectsBulk(anyList());
        verify(imageOperationRecoveryService).scheduleDeleteObjects(
                operationId,
                ImageOperationOwnerType.CHAT_ROOM,
                CHAT_ROOM_ID,
                List.of(CHAT_OLD_KEY, CHAT_SOURCE_KEY)
        );
    }

    @Test
    @DisplayName("채팅방 프로필 Copy 실패 시 기존 DB 이미지와 object를 유지한다")
    void updateChatRoomProfileImage_keepsExistingImageWhenCopyFails() {
        Image chatImage = prepareChatRoomStagingUpdate();
        doThrow(new IllegalStateException("copy failed"))
                .when(imageCopyExecutor).copy(CHAT_SOURCE_KEY, CHAT_TARGET_KEY);

        assertThatThrownBy(() -> profileImageService.updateChatRoomProfileImage(CHAT_ROOM_ID, CHAT_SOURCE_KEY))
                .isInstanceOf(BusinessException.class);

        assertThat(chatImage.getUrl()).isEqualTo(CDN_BASE_URL + "/" + CHAT_OLD_KEY);
        verify(imageOperationStepService).markTerminalFailed(stepId, "copy failed");
        verify(imageOperationService).markFailed(operationId);
        verify(imageRepository, never()).flush();
        verify(imageRepository, never()).deleteByImageTypeAndRelatedIdWithFlushing(any(), any());
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    @DisplayName("채팅방 프로필 Copy 성공 후 DB flush 실패 시 final object 보상을 예약한다")
    void updateChatRoomProfileImage_createsCompensationWhenDbFails() {
        prepareChatRoomStagingUpdate();
        prepareChatRoomCopySuccess();
        doThrow(new DataAccessResourceFailureException("db down")).when(imageRepository).flush();

        assertThatThrownBy(() -> profileImageService.updateChatRoomProfileImage(CHAT_ROOM_ID, CHAT_SOURCE_KEY))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(imageOperationRecoveryService).scheduleCompensation(operationId, CHAT_TARGET_KEY);
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    private void prepareStagingUpdate() {
        when(storageClient.isDefaultUrlOrKey(anyString())).thenReturn(false);
        when(storageClient.isStagingKey(SOURCE_KEY)).thenReturn(true);
        when(storageClient.extOf(SOURCE_KEY)).thenReturn("jpg");
        when(storageClient.headObject(SOURCE_KEY)).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(1024L)
                        .contentType("image/jpeg")
                        .eTag("\"source-etag\"")
                        .build()
        );
        when(imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, USER_ID))
                .thenReturn(Optional.of(existingImage));
        when(imageOperationService.createCopyOperation(
                eq(ImageOperationType.UPDATE_USER_PROFILE_IMAGE),
                eq(ImageOperationOwnerType.USER),
                eq(USER_ID),
                eq(SOURCE_KEY),
                anyString(),
                eq("\"source-etag\""),
                eq(1024L)
        )).thenReturn(new ImageOperationService.CopyOperationPlan(operationId, stepId, TARGET_KEY));
        when(imageCopyExecutor.copyProfile(SOURCE_KEY, TARGET_KEY))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult(TARGET_KEY, "\"result-etag\""));
    }

    private Image prepareChatRoomStagingUpdate() {
        Image chatImage = Image.of(
                ImageType.CHAT_ROOM,
                CHAT_ROOM_ID,
                CDN_BASE_URL + "/" + CHAT_OLD_KEY,
                0,
                ImageModerationStatus.CLEAN,
                null
        );
        when(storageClient.isDefaultUrlOrKey(anyString())).thenReturn(false);
        when(storageClient.isStagingKey(CHAT_SOURCE_KEY)).thenReturn(true);
        when(storageClient.extOf(CHAT_SOURCE_KEY)).thenReturn("jpg");
        when(storageClient.headObject(CHAT_SOURCE_KEY)).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(2048L)
                        .contentType("image/jpeg")
                        .eTag("\"chat-source-etag\"")
                        .build()
        );
        when(imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.CHAT_ROOM,
                CHAT_ROOM_ID
        )).thenReturn(Optional.of(chatImage));
        when(imageOperationService.createCopyOperation(
                eq(ImageOperationType.UPDATE_CHAT_ROOM_PROFILE_IMAGE),
                eq(ImageOperationOwnerType.CHAT_ROOM),
                eq(CHAT_ROOM_ID),
                eq(CHAT_SOURCE_KEY),
                anyString(),
                eq("\"chat-source-etag\""),
                eq(2048L)
        )).thenReturn(new ImageOperationService.CopyOperationPlan(operationId, stepId, CHAT_TARGET_KEY));
        return chatImage;
    }

    private void prepareChatRoomCopySuccess() {
        when(imageCopyExecutor.copy(CHAT_SOURCE_KEY, CHAT_TARGET_KEY))
                .thenReturn(new ImageCopyExecutor.ImageCopyResult(CHAT_TARGET_KEY, "\"chat-result-etag\""));
    }
}
