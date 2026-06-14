package core.domain.user.service.withdrawal;

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
import core.domain.user.entity.User;
import core.domain.user.entity.UserTestBuilder;
import core.domain.user.repository.AdminOtpRepository;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.common.ImageType;
import core.global.userfeedback.UserFeedbackRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserWithdrawalCommandTest {

    private final User user = UserTestBuilder.builder().id(1L).build();
    private final UserWithdrawalContext context = new UserWithdrawalContext(user);

    @Test
    void paymentCommand_deletesPaymentDataInExistingOrder() {
        IapEntitlementRepository entitlementRepository = mock(IapEntitlementRepository.class);
        IapBonusGrantRepository bonusGrantRepository = mock(IapBonusGrantRepository.class);
        UserItemRepository userItemRepository = mock(UserItemRepository.class);
        IapPurchaseRepository purchaseRepository = mock(IapPurchaseRepository.class);
        PaymentDataCleanupCommand command = new PaymentDataCleanupCommand(
                entitlementRepository, bonusGrantRepository, userItemRepository, purchaseRepository
        );

        command.execute(context);

        var order = inOrder(entitlementRepository, bonusGrantRepository, userItemRepository, purchaseRepository);
        order.verify(entitlementRepository).deleteAllByUserId(1L);
        order.verify(bonusGrantRepository).deleteAllByUserId(1L);
        order.verify(userItemRepository).deleteAllByUserId(1L);
        order.verify(purchaseRepository).deleteAllByUserId(1L);
        assertThat(command.order()).isEqualTo(UserWithdrawalCommandOrder.PAYMENT_DATA);
    }

    @Test
    void reportAndVoteCommand_deletesAllRelatedRecords() {
        PostReportRepository postReportRepository = mock(PostReportRepository.class);
        ChatReportRepository chatReportRepository = mock(ChatReportRepository.class);
        VoteRecordRepository voteRecordRepository = mock(VoteRecordRepository.class);
        ReportAndVoteDataCleanupCommand command = new ReportAndVoteDataCleanupCommand(
                postReportRepository, chatReportRepository, voteRecordRepository
        );

        command.execute(context);

        verify(postReportRepository).deleteAllByReporterId(1L);
        verify(chatReportRepository).deleteAllByReporterUserId(1L);
        verify(postReportRepository).deleteAllByReportedUserId(1L);
        verify(chatReportRepository).deleteAllByReportedUserId(1L);
        verify(voteRecordRepository).deleteAllByUserId(1L);
    }

    @Test
    void chatCommand_transfersOwnedRoomAndDeletesUserChatData() {
        ChatRoomRepository roomRepository = mock(ChatRoomRepository.class);
        ChatParticipantRepository participantRepository = mock(ChatParticipantRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatRoom room = mock(ChatRoom.class);
        ChatParticipant participant = mock(ChatParticipant.class);
        User newOwner = UserTestBuilder.builder().id(2L).build();
        when(room.getId()).thenReturn(10L);
        when(roomRepository.findAllByOwnerId(1L)).thenReturn(List.of(room));
        when(participantRepository.findAllByChatRoomIdAndUserIdNot(10L, 1L)).thenReturn(List.of(participant));
        when(participant.getUser()).thenReturn(newOwner);
        ChatDataCleanupCommand command = new ChatDataCleanupCommand(
                roomRepository, participantRepository, messageRepository
        );

        command.execute(context);

        verify(room).changeOwner(newOwner);
        verify(roomRepository).save(room);
        verify(roomRepository, never()).delete(room);
        verify(participantRepository).deleteAllByUserId(1L);
        verify(messageRepository).deleteAllBySenderId(1L);
    }

    @Test
    void chatCommand_deletesOwnedRoomWithoutRemainingParticipant() {
        ChatRoomRepository roomRepository = mock(ChatRoomRepository.class);
        ChatParticipantRepository participantRepository = mock(ChatParticipantRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(10L);
        when(roomRepository.findAllByOwnerId(1L)).thenReturn(List.of(room));
        when(participantRepository.findAllByChatRoomIdAndUserIdNot(10L, 1L)).thenReturn(List.of());
        ChatDataCleanupCommand command = new ChatDataCleanupCommand(
                roomRepository, participantRepository, messageRepository
        );

        command.execute(context);

        verify(roomRepository).delete(room);
        verify(roomRepository, never()).save(room);
    }

    @Test
    void communityCommand_deletesPostRelationsBeforePostsAndUserRelations() {
        BlockPostRepository blockPostRepository = mock(BlockPostRepository.class);
        PostRepository postRepository = mock(PostRepository.class);
        CommentRepository commentRepository = mock(CommentRepository.class);
        BookmarkRepository bookmarkRepository = mock(BookmarkRepository.class);
        PostReportRepository postReportRepository = mock(PostReportRepository.class);
        VoteRecordRepository voteRecordRepository = mock(VoteRecordRepository.class);
        LikeRepository likeRepository = mock(LikeRepository.class);
        FollowRepository followRepository = mock(FollowRepository.class);
        Post post = mock(Post.class);
        Poll poll = mock(Poll.class);
        when(post.getPoll()).thenReturn(poll);
        when(postRepository.findAllByAuthorId(1L)).thenReturn(List.of(post));
        CommunityDataCleanupCommand command = new CommunityDataCleanupCommand(
                blockPostRepository,
                postRepository,
                commentRepository,
                bookmarkRepository,
                postReportRepository,
                voteRecordRepository,
                likeRepository,
                followRepository
        );

        command.execute(context);

        var postDeletionOrder = inOrder(
                commentRepository, bookmarkRepository, postReportRepository, voteRecordRepository, postRepository
        );
        postDeletionOrder.verify(commentRepository).deleteAllByPostIn(List.of(post));
        postDeletionOrder.verify(bookmarkRepository).deleteAllByPostIn(List.of(post));
        postDeletionOrder.verify(postReportRepository).deleteAllByPostIn(List.of(post));
        postDeletionOrder.verify(voteRecordRepository).deleteAllByPollIn(List.of(poll));
        postDeletionOrder.verify(postRepository).deleteAll(List.of(post));
        verify(commentRepository).deleteAllByAuthorId(1L);
        verify(bookmarkRepository).deleteAllByUserId(1L);
        verify(likeRepository).deleteAllByUserId(1L);
        verify(followRepository).deleteAllByUserId(1L);
    }

    @Test
    void userOwnedDataCommand_deletesAllUserOwnedData() {
        ImageRepository imageRepository = mock(ImageRepository.class);
        ImageService imageService = mock(ImageService.class);
        AdminOtpRepository adminOtpRepository = mock(AdminOtpRepository.class);
        BlockRepository blockRepository = mock(BlockRepository.class);
        UserNotificationSettingRepository settingRepository = mock(UserNotificationSettingRepository.class);
        UserDeviceTokenRepository tokenRepository = mock(UserDeviceTokenRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        UserFeedbackRepository feedbackRepository = mock(UserFeedbackRepository.class);
        UserOwnedDataCleanupCommand command = new UserOwnedDataCleanupCommand(
                imageRepository,
                imageService,
                adminOtpRepository,
                blockRepository,
                settingRepository,
                tokenRepository,
                notificationRepository,
                feedbackRepository
        );

        command.execute(context);

        verify(imageRepository).deleteAllByImageTypeAndRelatedId(ImageType.USER, 1L);
        verify(imageService).deleteUserProfileImage(1L);
        verify(adminOtpRepository).deleteAllByUserId(1L);
        verify(blockRepository).deleteAllByUserOrBlocked(user);
        verify(settingRepository).deleteAllByUserId(1L);
        verify(tokenRepository).deleteAllByUserId(1L);
        verify(notificationRepository).deleteAllByUserId(1L);
        verify(notificationRepository).deleteAllByActorId(1L);
        verify(feedbackRepository).deleteAllByUserIdExplicit(1L);
    }

    @Test
    void userDeleteCommand_deletesUserLast() {
        UserRepository userRepository = mock(UserRepository.class);
        UserDeleteCommand command = new UserDeleteCommand(userRepository);

        command.execute(context);

        verify(userRepository).delete(user);
        assertThat(command.order()).isEqualTo(UserWithdrawalCommandOrder.USER_DELETE);
    }
}
