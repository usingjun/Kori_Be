package core.domain.user.service;

import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
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
import core.domain.poll.entity.Poll;
import core.domain.poll.repository.VoteRecordRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostReportRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.UserWithdrawalEvent;
import core.domain.user.entity.User;
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
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.userfeedback.UserFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 회원 탈퇴 유스케이스의 단일 진입점.
 *
 * 각 도메인의 삭제 작업을 FK 제약에 맞는 순서로 조율하고,
 * 계정 삭제가 커밋된 뒤 처리할 토큰 정리 이벤트를 발행한다.
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final AppleWithdrawalService appleWithdrawalService;
    private final ApplicationEventPublisher eventPublisher;

    private final IapEntitlementRepository iapEntitlementRepository;
    private final IapBonusGrantRepository iapBonusGrantRepository;
    private final UserItemRepository userItemRepository;
    private final IapPurchaseRepository iapPurchaseRepository;

    private final PostReportRepository postReportRepository;
    private final ChatReportRepository chatReportRepository;
    private final VoteRecordRepository voteRecordRepository;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;

    private final BlockPostRepository blockPostRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final LikeRepository likeRepository;
    private final FollowRepository followRepository;

    private final ImageRepository imageRepository;
    private final ImageService imageService;
    private final AdminOtpRepository adminOtpRepository;
    private final BlockRepository blockRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserFeedbackRepository userFeedbackRepository;

    @Transactional
    public boolean withdraw(Long userId, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        boolean appleAccount = Oauthplatform.APPLE.toString().equals(user.getProvider());
        if (appleAccount) {
            appleWithdrawalService.revokeAppleToken(user);
        }

        cleanupPaymentData(userId);
        cleanupReportAndVoteData(userId);
        cleanupChatData(user);
        cleanupCommunityData(user);
        cleanupUserOwnedData(user);
        userRepository.delete(user);

        eventPublisher.publishEvent(new UserWithdrawalEvent(userId, accessToken));
        return appleAccount;
    }

    private void cleanupPaymentData(Long userId) {
        iapEntitlementRepository.deleteAllByUserId(userId);
        iapBonusGrantRepository.deleteAllByUserId(userId);
        userItemRepository.deleteAllByUserId(userId);
        iapPurchaseRepository.deleteAllByUserId(userId);
    }

    private void cleanupReportAndVoteData(Long userId) {
        postReportRepository.deleteAllByReporterId(userId);
        chatReportRepository.deleteAllByReporterUserId(userId);
        postReportRepository.deleteAllByReportedUserId(userId);
        chatReportRepository.deleteAllByReportedUserId(userId);
        voteRecordRepository.deleteAllByUserId(userId);
    }

    private void cleanupChatData(User user) {
        Long userId = user.getId();
        List<ChatRoom> ownedChatRooms = chatRoomRepository.findAllByOwnerId(userId);

        for (ChatRoom chatRoom : ownedChatRooms) {
            List<ChatParticipant> participants =
                    chatParticipantRepository.findAllByChatRoomIdAndUserIdNot(chatRoom.getId(), userId);
            if (participants.isEmpty()) {
                chatRoomRepository.delete(chatRoom);
                continue;
            }
            chatRoom.changeOwner(participants.get(0).getUser());
            chatRoomRepository.save(chatRoom);
        }

        chatParticipantRepository.deleteAllByUserId(userId);
        chatMessageRepository.deleteAllBySenderId(userId);
    }

    private void cleanupCommunityData(User user) {
        Long userId = user.getId();
        blockPostRepository.deleteAllBlockPostsRelatedToUser(userId);

        List<Post> userPosts = postRepository.findAllByAuthorId(userId);
        if (userPosts != null && !userPosts.isEmpty()) {
            commentRepository.deleteAllByPostIn(userPosts);
            bookmarkRepository.deleteAllByPostIn(userPosts);
            postReportRepository.deleteAllByPostIn(userPosts);

            List<Poll> userPolls = userPosts.stream()
                    .map(Post::getPoll)
                    .filter(Objects::nonNull)
                    .toList();
            if (!userPolls.isEmpty()) {
                voteRecordRepository.deleteAllByPollIn(userPolls);
            }
            postRepository.deleteAll(userPosts);
        }

        commentRepository.deleteAllByAuthorId(userId);
        bookmarkRepository.deleteAllByUserId(userId);
        likeRepository.deleteAllByUserId(userId);
        followRepository.deleteAllByUserId(userId);
    }

    private void cleanupUserOwnedData(User user) {
        Long userId = user.getId();
        imageRepository.deleteAllByImageTypeAndRelatedId(ImageType.USER, userId);
        imageService.deleteUserProfileImage(userId);
        adminOtpRepository.deleteAllByUserId(userId);
        blockRepository.deleteAllByUserOrBlocked(user);
        userNotificationSettingRepository.deleteAllByUserId(userId);
        userDeviceTokenRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByActorId(userId);
        userFeedbackRepository.deleteAllByUserIdExplicit(userId);
    }
}
