package core.domain.user.service;

import core.domain.admin.dto.AiUserListDto;
import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.dto.RecentMessageDto;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatRoomService;
import core.domain.comment.dto.RecentCommentDto;
import core.domain.comment.repository.CommentRepository;
import core.domain.notification.repository.NotificationRepository;
import core.domain.post.dto.admin.RecentPostDto;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.*;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.domain.aiuser.entity.AiPersona;
import core.domain.aiuser.repository.AiPersonaRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.image.service.ProfileImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.*;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.user.FollowStatus;
import core.global.enums.user.Role;
import core.global.exception.BusinessException;
import core.global.userfeedback.UserFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final LikeRepository likeRepository;
    private final ImageRepository imageRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final BlockPostRepository blockPostRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final UserFeedbackRepository userFeedbackRepository;
    private final ImageService imageService;
    private final PasswordEncoder passwordEncoder;
    private final ProfileImageService profileImageService;
    private final AiPersonaRepository aiPersonaRepository;
    private final ChatRoomService chatRoomService;

    @Transactional(readOnly = true)
    public UserBasicInfoDto getUserBasicInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        return UserBasicInfoDto.from(user);
    }

    @Transactional(readOnly = true)
    public Page<UserListResponse> searchUsers(UserSearchRequest request, Pageable pageable) {
        Page<User> userPage = userRepository.searchUsers(request, pageable);
        return userPage.map(UserListResponse::from);
    }

    /**
     * 사용자의 친구 목록 (수락된 관계)을 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<FollowingInfoDto> getFollowingsForUser(Long userId, Pageable pageable) {
        Page<Follow> acceptedFollowsPage = followRepository.findAllAcceptedFollowsByUserId(userId, FollowStatus.ACCEPTED, pageable);
        return acceptedFollowsPage.map(follow -> {
            User friend = follow.getUser().getId().equals(userId) ? follow.getFollowing() : follow.getUser();
            return new FollowingInfoDto(friend.getId(), friend.getFirstName()+" "+friend.getLastName(), friend.getEmail());
        });
    }

    /**
     * 사용자가 차단한 유저 목록을 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<BlockedUserInfoDto> getBlockedUsersForUser(Long userId, Pageable pageable) {
        return blockRepository.findByUserId(userId, pageable)
                .map(BlockedUserInfoDto::from);
    }

    /**
     * 사용자가 참여 중인 채팅방 목록을 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<ChatRoomInfoDto> getChatRoomsForUser(Long userId, Pageable pageable) {
        Page<ChatParticipant> participants = chatParticipantRepository.findByUserIdAndStatus(
                userId,
                ChatParticipantStatus.ACTIVE,
                pageable
        );

        return participants.map(participant -> {
            ChatRoom room = participant.getChatRoom();
            String roomName = room.getRoomName();

            if (!room.getIsGroup()) {
                List<String> names = chatParticipantRepository.findParticipantNamesByRoomId(room.getId());
                if (!names.isEmpty()) {
                    roomName = String.join(", ", names);
                } else {
                    roomName = "(참여자 없음)";
                }
            }

            long messageCount = chatMessageRepository.countByChatRoomId(room.getId());

            return new ChatRoomInfoDto(
                    room.getId(),
                    roomName,
                    room.getIsGroup(),
                    room.getParticipants().size(),
                    messageCount // [추가] DTO에 전달
            );
        });
    }

    @Transactional
    public void deletePost(Long postId) {
        postRepository.deleteById(postId);
    }

    @Transactional
    public void deleteComment(Long commentId) {
        commentRepository.deleteById(commentId);
    }

    @Transactional
    public void deleteMessage(Long messageId) {
        chatMessageRepository.deleteById(messageId);
    }

    @Transactional
    public void deleteChatRoom(Long chatRoomId) {
        chatMessageRepository.deleteAllByChatRoomId(chatRoomId);
        chatRoomRepository.deleteById(chatRoomId);
    }

    /**
     * 특정 사용자를 DB에서 영구적으로 삭제합니다. (Hard Delete)
     * @param userId 삭제할 사용자의 ID
     */
    @Transactional
    public void hardDeleteUser(Long userId) {
        // 1. 유저 조회 (없으면 바로 종료 or 예외 처리)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 채팅방 방장 위임 처리
        List<ChatRoom> ownedChatRooms = chatRoomRepository.findAllByOwnerId(userId);
        for (ChatRoom chatRoom : ownedChatRooms) {
            // 방장 제외 다른 참여자 조회
            List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomIdAndUserIdNot(chatRoom.getId(), userId);

            if (!participants.isEmpty()) {
                User newOwner = participants.get(0).getUser();
                chatRoom.changeOwner(newOwner);
            } else {
                // 참여자가 없으면 방 폭파 (연관된 메시지, 참여자 정보도 같이 삭제되어야 함)
                // Cascade 설정이 안 되어 있다면 아래처럼 수동 삭제 필요
                chatMessageRepository.deleteByChatRoomId(chatRoom.getId());
                chatParticipantRepository.deleteByChatRoomId(chatRoom.getId());
                chatRoomRepository.delete(chatRoom);
            }
        }

        // 3. 연관 데이터 삭제 (순서 중요: 자식 -> 부모)

        // 게시글 관련
        List<Post> userPosts = postRepository.findAllByAuthorId(userId);
        if (!userPosts.isEmpty()) {
            commentRepository.deleteAllByPostIn(userPosts);
            bookmarkRepository.deleteAllByPostIn(userPosts);
            postRepository.deleteAll(userPosts);
        }

        // 나머지 단순 삭제들 (반드시 Repository에 @Modifying @Query 적용 권장)
        blockPostRepository.deleteAllBlockPostsRelatedToUser(userId);
        commentRepository.deleteAllByAuthorId(userId);
        bookmarkRepository.deleteAllByUserId(userId);
        followRepository.deleteAllByUserId(userId);
        likeRepository.deleteAllByUserId(userId);

        // [중요] 메시지를 먼저 지우고 참여 정보를 지우는 것이 FK 관점에서 안전
        chatMessageRepository.deleteAllBySenderId(userId);
        chatParticipantRepository.deleteAllByUserId(userId);

        imageRepository.deleteAllByImageTypeAndRelatedId(ImageType.USER, userId);
        imageService.deleteUserProfileImage(userId);

        // 차단 목록은 User 객체가 필요하므로 여기서 처리
        blockRepository.deleteAllByUserOrBlocked(user);

        userNotificationSettingRepository.deleteAllByUserId(userId);
        userDeviceTokenRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByActorId(userId);
        userFeedbackRepository.deleteAllByUserIdExplicit(userId);
        aiPersonaRepository.deleteByUserId(userId);

        // 4. 마지막으로 유저 삭제
        userRepository.delete(user);

        log.info(">>>> Deleted user entity for userId: {}", userId);
    }

    @Transactional
    public void forceUserLeaveChatRoom(Long userId, Long chatRoomId) {
        chatRoomService.leaveRoom(chatRoomId, userId);
    }

    @Transactional(readOnly = true)
    public Page<RecentPostDto> getRecentPostsForUser(Long userId, Pageable pageable) {
        return postRepository.findByAuthorId(userId, pageable)
                .map(RecentPostDto::from);
    }

    @Transactional(readOnly = true)
    public Page<RecentCommentDto> getRecentCommentsForUser(Long userId, Pageable pageable) {
        return commentRepository.findByAuthorId(userId, pageable)
                .map(RecentCommentDto::from);
    }

    @Transactional(readOnly = true)
    public Page<RecentMessageDto> getRecentMessagesForUser(Long userId, Pageable pageable) {
        return chatMessageRepository.findBySenderId(userId, pageable)
                .map(RecentMessageDto::from);
    }

    @Transactional
    public void createAiUser(UserSetupRequest dto, String password, MultipartFile profileFile,
                             String instruction, String backgroundInfo, AiType aiType) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String aiEmail;

        if (dto.email() != null && !dto.email().isBlank()) {
            aiEmail = dto.email().trim();
        } else {
            aiEmail = "ai-" + uuid + "@system.bot";
        }

        String provider = "AI_BOT";

        User aiUser = User.builder()
                .firstName(dto.firstname())
                .lastName(dto.lastname())
                .email(aiEmail)
                .provider(provider)
                .socialId(uuid)
                .sex(dto.gender())
                .birthdate(dto.birthday())
                .country(dto.country())
                .purpose(dto.purpose())
                .introduction(dto.introduction())
                .createdAt(Instant.now())
                .build();

        if (password != null && !password.isBlank()) {
            aiUser.updatePassword(passwordEncoder.encode(password));
        }

        if (dto.language() != null && !dto.language().isEmpty()) {
            List<String> rawLanguages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!rawLanguages.isEmpty()) {
                String firstTranslatedLanguage = rawLanguages.stream()
                        .findFirst()
                        .map(s -> normalizeLanguageCode(s).toLowerCase())
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    aiUser.updateTranslateLanguage(firstTranslatedLanguage);
                }

                List<String> normalizedLanguagesForCsv = rawLanguages.stream()
                        .map(s -> normalizeLanguageCode(s).toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();

                if (!normalizedLanguagesForCsv.isEmpty()) {
                    aiUser.updateLanguage(String.join(",", normalizedLanguagesForCsv));
                }
            }
        }

        if (dto.hobby() != null && !dto.hobby().isEmpty()) {
            aiUser.updateHobby(String.join(",", dto.hobby()));
        }

        aiUser.updateIsNewUser(false);
        aiUser.changeUserRole(Role.AI);

        userRepository.save(aiUser);

        AiPersona persona = AiPersona.builder()
                .userId(aiUser.getId())
                .aiType(aiType)
                .instruction(instruction)
                .backgroundInfo(backgroundInfo)
                .isActive(true)
                .build();

        aiPersonaRepository.save(persona);

        if (profileFile != null && !profileFile.isEmpty()) {
            profileImageService.uploadUserProfileImage(aiUser.getId(), profileFile);
        }
        else if (dto.imageKey() != null && !dto.imageKey().isBlank()) {
            imageService.saveUserProfileImage(aiUser.getId(), dto.imageKey());
        }
    }

    @Transactional(readOnly = true)
    public UserSetupRequest getAiUserForEdit(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<String> languages = Collections.emptyList();
        if (StringUtils.hasText(user.getLanguage())) {
            languages = Arrays.asList(user.getLanguage().split(","));
        }

        List<String> hobbies = Collections.emptyList();
        if (StringUtils.hasText(user.getHobby())) {
            hobbies = Arrays.asList(user.getHobby().split(","));
        }

        String currentImageUrl = profileImageService.getUserProfileKey(userId);

        return new UserSetupRequest(user, languages, hobbies, currentImageUrl);
    }

    @Transactional
    public void updateAiUser(Long userId, UserSetupRequest dto, String password, MultipartFile profileFile,
                             String instruction, String backgroundInfo, AiType aiType) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.updateFirstName(dto.firstname());
        user.updateLastName(dto.lastname());
        user.updateSex(dto.gender());
        user.updateBirthdate(dto.birthday());
        user.updateCountry(dto.country());
        user.updatePurpose(dto.purpose());
        user.updateIntroduction(dto.introduction());

        if (StringUtils.hasText(password)) {
            user.updatePassword(passwordEncoder.encode(password));
        }

        if (dto.language() != null && !dto.language().isEmpty()) {
            List<String> processedLanguages = dto.language().stream()
                    .filter(StringUtils::hasText)
                    .flatMap(s -> Arrays.stream(s.split(",")))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(this::normalizeLanguageCode)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            if (!processedLanguages.isEmpty()) {
                String langStr = String.join(",", processedLanguages);
                user.updateLanguage(langStr);
            }
        }

        if (dto.hobby() != null && !dto.hobby().isEmpty()) {
            String hobbyStr = String.join(",", dto.hobby());
            user.updateHobby(hobbyStr);
        }

        AiPersona persona = aiPersonaRepository.findByUserId(userId)
                .orElse(null);

        if (persona != null) {
            persona.updatePersona(aiType, instruction, backgroundInfo);
        } else {
            AiPersona newPersona = AiPersona.builder()
                    .userId(userId)
                    .aiType(aiType)
                    .instruction(instruction)
                    .backgroundInfo(backgroundInfo)
                    .isActive(true)
                    .build();
            aiPersonaRepository.save(newPersona);
        }

        if (profileFile != null && !profileFile.isEmpty()) {
            profileImageService.uploadUserProfileImage(userId, profileFile);
        }

        else if (StringUtils.hasText(dto.imageKey())) {
            profileImageService.updateUserProfileImage(userId, dto.imageKey());
        }
    }

    @Transactional(readOnly = true)
    public AiPersona getAiPersona(Long userId) {
        return aiPersonaRepository.findByUserId(userId).orElse(null);
    }



    private String normalizeLanguageCode(String rawLang) {
        if (rawLang == null) return "";
        String normalized = rawLang.toLowerCase().trim();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[([a-zA-Z]{2,3})\\]");

        java.util.regex.Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        if (normalized.contains("-")) {
            return normalized.split("-")[0].trim();
        }
        if (normalized.length() >= 2 && normalized.length() <= 5) {
            return normalized;
        }
        return "";
    }

    @Transactional(readOnly = true)
    public List<AiUserListDto> getAiUsers() {
        List<User> aiUsers = userRepository.findAllByUserRole(Role.AI);
        List<AiUserListDto> result = new ArrayList<>();

        for (User user : aiUsers) {
            AiPersona persona = aiPersonaRepository.findByUserId(user.getId()).orElse(null);
            AiType type = (persona != null) ? persona.getAiType() : AiType.ALL;

            result.add(new AiUserListDto(user, type));
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<ChatRoom> getGroupChatRooms() {
        return chatRoomRepository.findByIsGroupTrue();
    }

    @Transactional
    public void addAiToChatRoom(Long userId, Long chatRoomId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (user.getUserRole() != Role.AI) {
            throw new BusinessException(UserErrorCode.NOT_AI_USER);
        }

        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        if (!chatRoom.getIsGroup()) {
            throw new BusinessException(ChatErrorCode.CHAT_NOT_GROUP);
        }

        chatParticipantRepository.findByChatRoomIdAndUserId(chatRoomId, userId)
                .ifPresentOrElse(
                        participant -> {
                            if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
                                throw new BusinessException(ChatErrorCode.ALREADY_CHAT_PARTICIPANT, "이미 해당 채팅방에 참여 중입니다.");
                            } else {
                                participant.reJoin();
                            }
                        },
                        () -> {
                            ChatParticipant newParticipant = new ChatParticipant(chatRoom, user);

                            chatRoom.addParticipant(newParticipant);
                            chatParticipantRepository.save(newParticipant);
                        }
                );
    }

    public boolean isAiUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return user.getUserRole() == Role.AI;
    }
}
