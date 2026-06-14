package core.domain.user.service;

import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatReportRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.notification.repository.NotificationRepository;
import core.domain.payment.repository.IapBonusGrantRepository;
import core.domain.payment.repository.IapEntitlementRepository;
import core.domain.payment.repository.IapPurchaseRepository;
import core.domain.payment.repository.UserItemRepository;
import core.domain.poll.repository.VoteRecordRepository;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostReportRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.UserWithdrawalEvent;
import core.domain.user.entity.User;
import core.domain.user.entity.UserTestBuilder;
import core.domain.user.repository.AdminOtpRepository;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.apple.service.AppleWithdrawalService;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.Oauthplatform;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.userfeedback.UserFeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @Mock UserRepository userRepository;
    @Mock AppleWithdrawalService appleWithdrawalService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock IapEntitlementRepository iapEntitlementRepository;
    @Mock IapBonusGrantRepository iapBonusGrantRepository;
    @Mock UserItemRepository userItemRepository;
    @Mock IapPurchaseRepository iapPurchaseRepository;
    @Mock PostReportRepository postReportRepository;
    @Mock ChatReportRepository chatReportRepository;
    @Mock VoteRecordRepository voteRecordRepository;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatParticipantRepository chatParticipantRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock BlockPostRepository blockPostRepository;
    @Mock PostRepository postRepository;
    @Mock CommentRepository commentRepository;
    @Mock BookmarkRepository bookmarkRepository;
    @Mock LikeRepository likeRepository;
    @Mock FollowRepository followRepository;
    @Mock ImageRepository imageRepository;
    @Mock ImageService imageService;
    @Mock AdminOtpRepository adminOtpRepository;
    @Mock BlockRepository blockRepository;
    @Mock UserNotificationSettingRepository userNotificationSettingRepository;
    @Mock UserDeviceTokenRepository userDeviceTokenRepository;
    @Mock NotificationRepository notificationRepository;
    @Mock UserFeedbackRepository userFeedbackRepository;

    @InjectMocks UserWithdrawalService withdrawalService;

    @Test
    void withdraw_throwsWhenUserDoesNotExist() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(1L, "access-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND));

        verify(userRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void withdraw_revokesAppleAccountCleansDataAndPublishesEvent() {
        User user = UserTestBuilder.builder().id(1L).build();
        user.updateProvider(Oauthplatform.APPLE.toString());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        boolean appleAccount = withdrawalService.withdraw(1L, "access-token");

        assertThat(appleAccount).isTrue();
        verify(appleWithdrawalService).revokeAppleToken(user);
        verify(imageService).deleteUserProfileImage(1L);
        verify(userRepository).delete(user);

        ArgumentCaptor<UserWithdrawalEvent> eventCaptor =
                ArgumentCaptor.forClass(UserWithdrawalEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().getAccessToken()).isEqualTo("access-token");

        InOrder deletionOrder = inOrder(
                iapEntitlementRepository,
                iapBonusGrantRepository,
                userItemRepository,
                iapPurchaseRepository,
                userRepository,
                eventPublisher
        );
        deletionOrder.verify(iapEntitlementRepository).deleteAllByUserId(1L);
        deletionOrder.verify(iapBonusGrantRepository).deleteAllByUserId(1L);
        deletionOrder.verify(userItemRepository).deleteAllByUserId(1L);
        deletionOrder.verify(iapPurchaseRepository).deleteAllByUserId(1L);
        deletionOrder.verify(userRepository).delete(user);
        deletionOrder.verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(UserWithdrawalEvent.class));
    }

    @Test
    void withdraw_doesNotRevokeNonAppleAccount() {
        User user = UserTestBuilder.builder().id(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        boolean appleAccount = withdrawalService.withdraw(1L, "access-token");

        assertThat(appleAccount).isFalse();
        verify(appleWithdrawalService, never()).revokeAppleToken(user);
        verify(userRepository).delete(user);
    }
}
