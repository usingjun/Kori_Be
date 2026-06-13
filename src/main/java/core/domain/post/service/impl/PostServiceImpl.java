package core.domain.post.service.impl;

import core.domain.board.dto.BoardItem;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.maincontent.entity.MainContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.domain.maincontent.service.search.MainContentHotKeywordBatchService;
import core.domain.notification.dto.NotificationEvent;
import core.domain.poll.entity.PollOption;
import core.domain.poll.repository.PollOptionRepository;
import core.domain.poll.repository.VoteRecordRepository;
import core.domain.post.dto.admin.PostReportRequest;
import core.domain.post.dto.comunity.*;
import core.domain.post.entity.BlockPost;
import core.domain.post.entity.Post;
import core.domain.post.entity.PostReport;
import core.domain.post.event.PostCreatedEvent;
import core.domain.post.event.PostUpdatedEvent;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostReportRepository;
import core.domain.post.repository.PostRepository;
import core.domain.post.service.PostService;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.like.entity.Like;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.*;
import core.global.enums.common.CommunitySortOption;
import core.global.enums.common.ImageType;
import core.global.enums.common.LikeType;
import core.global.enums.community.BoardCategory;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.user.FollowStatus;
import core.global.exception.BusinessException;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import core.global.pagination.CursorPages;
import core.global.service.ForbiddenWordService;
import core.global.service.TranslationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static core.global.enums.errorcode.CommunityErrorCode.POST_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final int FLOOD_WINDOW_MINUTES = 5;  // 도배 판단 기준 시간
    private static final int FLOOD_MAX_POSTS = 3;       // 5분 동안 허용할 최대 게시글 수
    private static final int DUP_WINDOW_MINUTES = 5;
    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final ForbiddenWordService forbiddenWordService;
    private final ImageService imageService;
    private final BlockRepository blockRepository;
    private final BlockPostRepository blockPostRepository;
    private final TranslationService translationService;
    private final UserRoleDetectService userRoleDetectService;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PostReportRepository postReportRepository;
    private final MainContentHotKeywordBatchService recommendBatchService;
    private final PollOptionRepository pollOptionRepository;
    private final VoteRecordRepository voteRecordRepository;

    private final MainContentRepository mainContentRepository;
    private final ImageStorageClient imageStorageClient;

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<BoardItem> getPostList(Long boardId, CommunitySortOption sort, String cursor, int size) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        final Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        final int pageSize = Math.min(Math.max(size, 1), 50);
        final Map<String, Object> c = safeDecode(cursor);

        return switch (sort) {
            case POPULAR -> handlePopular(user.getId(), resolvedBoardId, c, pageSize);
            case LATEST -> handleLatest(user.getId(), resolvedBoardId, c, pageSize);
            default -> handleLatest(user.getId(), resolvedBoardId, c, pageSize);
        };
    }


    // ------- 정렬 핸들러 -------
    private CursorPageResponse<BoardItem> handleLatest(Long userId, Long boardId, Map<String, Object> c, int pageSize) {
        var k = parseLatest(c); // t,id
        List<BoardItem> rows = postRepository.findLatestPosts(
                userId,
                boardId,
                truncateToMillis(k.t),
                k.id,
                pageSize + 1
        );

        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        fillPollOptions(rows);

        return CursorPages.ofLatest(
                rows, pageSize,
                BoardItem::createdAt,
                BoardItem::id
        );
    }

    private CursorPageResponse<BoardItem> handlePopular(Long userId, Long boardId, Map<String, Object> c, int pageSize) {
        var k = parsePopular(c);
        Instant since = popularSince();
        List<BoardItem> rows = postRepository.findPopularPosts(
                userId,
                boardId,
                since,
                k.sc,
                k.id,
                pageSize + 1
        );


        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        fillPollOptions(rows);

        return CursorPages.ofPopular(
                rows, pageSize,
                BoardItem::score,
                BoardItem::id
        );
    }

    private void fillPollOptions(List<BoardItem> items) {
        List<Long> pollPostIds = items.stream()
                .filter(item -> item.pollInfo() != null)
                .map(BoardItem::id)
                .toList();

        if (pollPostIds.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                BoardItem item = items.get(i);
                if (item.pollInfo() != null && item.pollInfo().title() == null) {
                    // Record의 데이터를 복사하면서 pollInfo만 null로 바꾼 새 객체로 교체
                    items.set(i, createNonPollItem(item));
                }
            }
            return;
        }

        // 투표 옵션들을 IN 절로 한 번에 조회
        List<PollOption> allOptions = pollOptionRepository.findAllByPollIdIn(pollPostIds);

        // PostId별로 그룹화
        Map<Long, List<BoardItem.OptionItem>> optionsMap = allOptions.stream()
                .collect(Collectors.groupingBy(
                        po -> po.getPoll().getId(),
                        Collectors.mapping(po -> new BoardItem.OptionItem(
                                po.getId(), po.getContent(), po.getVoteCount()
                        ), Collectors.toList())
                ));

        // 데이터 매핑
        for (int i = 0; i < items.size(); i++) {
            BoardItem item = items.get(i);

            if (item.pollInfo() != null && item.pollInfo().title() != null) {

                // 해당 게시글에 맞는 옵션 리스트만 가져오기 (없으면 빈 리스트)
                List<BoardItem.OptionItem> specificOptions = optionsMap.getOrDefault(item.id(), List.of());

                // 🔥 핵심 수정: addAll() 대신 PollInfo와 BoardItem을 새로 생성합니다.
                // 이렇게 해야 QueryDSL이 만든 공유 리스트(ArrayList) 연결을 끊을 수 있습니다.
                BoardItem.PollInfo newPollInfo = new BoardItem.PollInfo(
                        item.pollInfo().title(),
                        item.pollInfo().description(),
                        item.pollInfo().closeAt(),
                        item.pollInfo().totalVoteCount(),
                        specificOptions, // ✅ DB에서 가져온 "내 옵션"만 주입
                        item.pollInfo().selectedOptionId(),
                        item.pollInfo().correctOptionId()
                );

                // BoardItem도 새로 생성해서 리스트 교체
                BoardItem newItem = new BoardItem(
                        item.id(), item.contentPreview(), item.authorId(), item.authorName(),
                        item.boardCategory(), item.createdAt(), item.isAnonymous(),
                        item.isLiked(), item.isBookmarked(), item.likeCount(),
                        item.commentCount(), item.viewCount(), item.userImageUrl(),
                        item.score(), item.postInfo().contentImageUrl(), item.postInfo().imageCount(),
                        item.postInfo(),
                        newPollInfo // ✅ 교체된 PollInfo
                );

                items.set(i, newItem); // 리스트의 요소를 교체

            } else {
                // 투표 없는 글 처리
                items.set(i, createNonPollItem(item));
            }
        }
    }

    private BoardItem createNonPollItem(BoardItem item) {
        return new BoardItem(
                item.id(), item.contentPreview(), item.authorId(), item.authorName(),
                item.boardCategory(), item.createdAt(), item.isAnonymous(),
                item.isLiked(), item.isBookmarked(), item.likeCount(),
                item.commentCount(), item.viewCount(), item.userImageUrl(),
                item.score(), item.postInfo().contentImageUrl(), item.postInfo().imageCount(),
                item.postInfo(), null // pollInfo를 null로 꽂아버림
        );
    }


    // ------- 커서 파싱 -------
    private LatestKey parseLatest(Map<String, Object> c) {
        Instant t = null;
        Long id = null;
        Object ts = c.get("t");
        if (ts instanceof String s && !s.isBlank()) t = Instant.parse(s);
        Object idObj = c.get("id");
        if (idObj instanceof Number n) id = n.longValue();
        return new LatestKey(t, id);
    }

    private PopularKey parsePopular(Map<String, Object> c) {
        Long sc = null, id = null;
        Object scObj = c.get("sc");
        if (scObj instanceof Number n) sc = n.longValue();
        Object idObj = c.get("id");
        if (idObj instanceof Number n) id = n.longValue();
        return new PopularKey(sc, id);
    }

    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            return CursorCodec.decode(cursor);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private Instant popularSince() {
        return Instant.now().minus(Duration.ofDays(30));
    }

    // ------- 유틸 -------
    private Instant truncateToMillis(Instant i) {
        return (i == null) ? null : i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }


    @Transactional(readOnly = true)
    public CursorPageResponse<BoardItem> getUserPostList(Long targetUserId, CommunitySortOption sort, String cursor, int size) {
        // 1. 현재 로그인한 유저 (좋아요 여부 등 판별용)
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User loggedInUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 조회하려는 타겟 유저가 존재하는지 검증
        if (!userRepository.existsById(targetUserId)) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        final int pageSize = Math.min(Math.max(size, 1), 50);
        final Map<String, Object> c = safeDecode(cursor);

        // 3. 타겟 유저 전용 핸들러로 분기
        return switch (sort) {
            case POPULAR -> handleUserPopular(loggedInUser.getId(), targetUserId, c, pageSize);
            case LATEST -> handleUserLatest(loggedInUser.getId(), targetUserId, c, pageSize);
            default -> handleUserLatest(loggedInUser.getId(), targetUserId, c, pageSize);
        };
    }


    private CursorPageResponse<BoardItem> handleUserLatest(Long loggedInUserId, Long targetUserId, Map<String, Object> c, int pageSize) {
        var k = parseLatest(c);

        List<BoardItem> rows = postRepository.findUserLatestPosts(
                loggedInUserId, // 로그인한 사람 (isLiked 등 판별)
                targetUserId,   // 조회할 대상 작성자 ID (WHERE author.id = targetUserId)
                truncateToMillis(k.t),
                k.id,
                pageSize + 1
        );

        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        fillPollOptions(rows);

        return CursorPages.ofLatest(rows, pageSize, BoardItem::createdAt, BoardItem::id);
    }

    private CursorPageResponse<BoardItem> handleUserPopular(Long loggedInUserId, Long targetUserId, Map<String, Object> c, int pageSize) {
        var k = parsePopular(c);
        Instant since = popularSince();

        List<BoardItem> rows = postRepository.findUserPopularPosts(
                loggedInUserId,
                targetUserId,
                since,
                k.sc,
                k.id,
                pageSize + 1
        );

        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        fillPollOptions(rows);

        return CursorPages.ofPopular(rows, pageSize, BoardItem::score, BoardItem::id);
    }

    @Override
    @Transactional
    public PostDetailResponse getPostDetail(Long postId, Boolean translate) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(POST_NOT_FOUND));

        if (blockRepository.existsBlockedByEmail(email, post.getAuthor().getEmail()) || blockRepository.existsBlockedByEmail(post.getAuthor().getEmail(), email)) {
            throw new BusinessException(CommunityErrorCode.BLOCKED_USER_POST);
        }

        postRepository.incrementViewCount(postId);

        PostDetailResponse postDetail = postRepository.findPostDetail(user.getId(), postId);

        if (translate) {
            String translatedContent = translationService.translatePost(postDetail.content(), user.getTranslateLanguage());
            return new PostDetailResponse(postDetail, translatedContent);
        }
        return postDetail;
    }

    @Override
    @Transactional
    public Long writePost(@Positive Long boardId, PostWriteRequest request) {
        if (boardId == 1) {
            throw new BusinessException(CommunityErrorCode.NOT_AVAILABLE_WRITE);
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

        validateAnonymousPolicy(board.getCategory(), request.isAnonymous());

        validatePostForbiddenWord(request.content());

        validateDuplicateContent(email, request.content());

        validatePostFlooding(email);

        final Post post = getPost(email, request, board);

        imageService.savePostImages(post.getId(), request.imageUrls());
        publishFollowerNotification(post);

        return post.getId();
    }

    /**
     * [새로 추가된 private 헬퍼 메소드]
     * 게시글 작성자의 팔로워들에게 알림을 발행합니다.
     *
     * @param post 새로 작성되고 저장된 게시글 엔티티
     */
    private void publishFollowerNotification(Post post) {
        User author = post.getAuthor();
        List<Follow> follows = followRepository.findAllByFollowingAndStatus(author, FollowStatus.ACCEPTED);

        for (Follow follow : follows) {
            User recipient = follow.getUser();

            if (recipient.getId().equals(author.getId())) {
                continue;
            }

            NotificationEvent event = new NotificationEvent(
                    recipient.getId(),
                    author.getId(),
                    NotificationType.followuserpost,
                    post.getId(),
                    null
            );
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    @Transactional
    public void writePostForChat(Long roomId, PostWriteForChatRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Board board = boardRepository.findByCategory(BoardCategory.ACTIVITY)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

        validateChatRoomPolicy(board.getCategory(), request.link());

        validatePostForbiddenWord(request.content());

        validateDuplicateContent(email, request.content());

        validatePostFlooding(email);

        final Post post = getPost(email, request, board);

        imageService.savePostImages(post.getId(), request.imageUrls());
    }

    private void validatePostForbiddenWord(String content) {
        List<String> forbiddenWords = forbiddenWordService.containsForbiddenWord(content);

        if (!forbiddenWords.isEmpty()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN_WORD_DETECTED, forbiddenWords);
        }
    }

    private void validateChatRoomPolicy(BoardCategory category, String link) {
        final boolean allowAnonymous =
                category == BoardCategory.FREE_TALK || category == BoardCategory.QNA;

        if (!allowAnonymous && !link.isEmpty()) {
            throw new BusinessException(CommunityErrorCode.NOT_AVAILABLE_LINK);
        }

    }

    private void validateAnonymousPolicy(BoardCategory category, Boolean isAnonymous) {
        final boolean allowAnonymous =
                category == BoardCategory.FREE_TALK || category == BoardCategory.QNA;

        if (!allowAnonymous && isAnonymous) {
            throw new BusinessException(CommunityErrorCode.NOT_AVAILABLE_ANONYMOUS);
        }
    }

    private Post getPost(String email, PostWriteRequest request, Board board) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        final Post post = new Post(request, user, board);
        eventPublisher.publishEvent(new PostCreatedEvent(post.getId(), post.getContent()));

        return postRepository.save(post);
    }

    private Post getPost(String email, PostWriteForChatRequest request, Board board) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        final Post post = new Post(request, user, board);
        eventPublisher.publishEvent(new PostCreatedEvent(post.getId(), post.getContent()));

        return postRepository.save(post);
    }

    @Override
    @Transactional
    public void updatePost(Long postId, @Valid PostUpdateRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        validatePostForbiddenWord(request.content());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(POST_NOT_FOUND));

        if (!email.equals(post.getAuthor().getEmail())) {
            throw new BusinessException(CommunityErrorCode.POST_EDIT_FORBIDDEN);
        }

        if (request.content() != null && !request.content().equals(post.getContent())) {
            post.changeContent(request.content());
        }

        imageService.updatePostImages(post.getId(), request.images(), request.removedImages());
        eventPublisher.publishEvent(new PostUpdatedEvent(post.getId(), post.getContent()));

    }

    @Override
    @Transactional
    public void deletePost(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(POST_NOT_FOUND));

        if (post.getAuthor() == null || !post.getAuthor().getEmail().equals(email)) {
            throw new BusinessException(CommunityErrorCode.POST_DELETE_FORBIDDEN);
        }

        imageService.deletePostImages(postId);
        postRepository.delete(post);
    }

    @Override
    @Transactional
    public void addLike(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        Optional<Like> existedLike = likeRepository.findLikeByUserEmailAndType(email, postId, LikeType.POST);
        if (existedLike.isPresent()) {
            throw new BusinessException(CommunityErrorCode.LIKE_ALREADY_EXIST);
        }

        likeRepository.save(Like.builder()
                .user(user)
                .type(LikeType.POST)
                .relatedId(postId)
                .build());
    }

    @Override
    @Transactional
    public void removeLike(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);

        likeRepository.deleteByUserEmailAndIdAndType(email, postId, LikeType.POST);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserPostItem> getMyPostList(String cursor, int size) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);


        final int pageSize = Math.min(Math.max(size, 1), 50);

        Instant cursorCreatedAt = null;
        Long cursorId = null;
        var payload = CursorCodec.decode(cursor);
        if (payload.get("t") instanceof String ts && !ts.isBlank()) cursorCreatedAt = Instant.parse(ts);
        if (payload.get("id") instanceof Number n) cursorId = n.longValue();

        List<UserPostItem> rows = (cursorId == null || cursorCreatedAt == null)
                ? postRepository.findMyPostsFirstByEmail(email, pageSize + 1)
                : postRepository.findMyPostsNextByEmail(email,
                cursorCreatedAt.truncatedTo(ChronoUnit.MILLIS),
                cursorId,
                pageSize + 1);

        boolean hasNext = rows.size() > pageSize;
        if (hasNext) rows = rows.subList(0, pageSize);

        if (rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        UserPostItem last = rows.get(rows.size() - 1);
        String nextCursor = hasNext
                ? CursorCodec.encode(Map.of(
                "t", last.createdAt().toString(),
                "id", last.id()
        ))
                : null;

        return new CursorPageResponse<>(rows, hasNext, nextCursor);
    }

    @Override
    public CommentWriteAnonymousAvailableResponse isAnonymousAvaliable(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(POST_NOT_FOUND));

        return new CommentWriteAnonymousAvailableResponse(post.getAnonymous());
    }

    @Override
    @Transactional
    public void blockUser(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);


        User blockedUser = postRepository.findUserByPostId(postId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (blockedUser.getEmail().equals(email)) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }

        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (blockRepository.existsBlock(me.getId(), blockedUser.getId()) || blockRepository.existsBlock(blockedUser.getId(), me.getId())) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }

        blockRepository.save(new BlockUser(me, blockedUser));
    }

    @Override
    @Transactional
    public void blockPost(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        userRoleDetectService.isProfileSetUpUser(user);


        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(POST_NOT_FOUND));

        if (post.getAuthor().getEmail().equals(email)) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }

        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (blockPostRepository.existsBlock(me.getId(), post.getId())) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }

        blockPostRepository.save(new BlockPost(me, post));
    }

    private void validatePostFlooding(String email) {
        Instant cutOff = Instant.now().minus(FLOOD_WINDOW_MINUTES, ChronoUnit.MINUTES);

        long recentPostCount =
                postRepository.countByAuthorEmailAndCreatedAtAfter(email, cutOff);

        if (recentPostCount >= FLOOD_MAX_POSTS) {
            throw new BusinessException(CommunityErrorCode.TOO_MANY_POSTS);
        }
    }

    private void validateDuplicateContent(String email, String rawContent) {
        // 1) 내용 정규화 (원하는 만큼만)
        String normalizedContent = normalizeContent(rawContent);

        // 2) 5분 전 시점
        Instant cutOff = Instant.now().minus(DUP_WINDOW_MINUTES, ChronoUnit.MINUTES);

        // 3) 같은 유저 + 같은 내용 + 5분 이내
        boolean exists = postRepository
                .existsByAuthorEmailAndContentAndCreatedAtAfter(email, normalizedContent, cutOff);

        if (exists) {
            throw new BusinessException(CommunityErrorCode.DUPLICATE_CONTENT);
        }
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return null;
        }

        String result = content;
        result = Normalizer.normalize(result, Normalizer.Form.NFC);
        result = result.replaceAll("\\s+", " ").trim();
        result = result.toLowerCase(Locale.ROOT);
        return result;
    }

    @Override
    @Transactional
    public void createAdminPost(String title, String content, String publishType,
                                String boardCategoryStr, String kNewsTypeStr,
                                List<MultipartFile> generalImages,
                                MultipartFile mainThumbnailFile, MultipartFile popularThumbnailFile,
                                List<MultipartFile> contentImages,
                                User adminUser, List<String> recommendationKeywords) throws IOException {

        if ("GENERAL".equals(publishType)) {
            BoardCategory category;
            try {
                category = BoardCategory.valueOf(boardCategoryStr.toUpperCase());
            } catch (Exception e) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }

            Board board = boardRepository.findByCategory(category)
                    .orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

            Post post = new Post(content, adminUser, board);
            Post savedPost = postRepository.save(post);

            if (generalImages != null && !generalImages.isEmpty()) {
                imageService.uploadAndSavePostImages(savedPost, generalImages);
            }

        } else if ("MAIN_PAGE".equals(publishType)) {
            if (title == null || title.trim().isEmpty()) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }

            KNewsContentType kNewsType = null;
            if (StringUtils.hasText(kNewsTypeStr)) {
                try {
                    kNewsType = KNewsContentType.valueOf(kNewsTypeStr);
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(CommonErrorCode.INVALID_INPUT);
                }
            }

            MainContent newContent = MainContent.builder()
                    .title(title)
                    .htmlContent(content)
                    .type(kNewsType)
                    .originalUrl(null)
                    .build();

            MainContent savedContent = mainContentRepository.save(newContent);
            Long contentId = savedContent.getId();

            String processedHtml = processHtmlAndUploadImages(content, contentId, contentImages);
            savedContent.changeHtmlContent(processedHtml);

            if (mainThumbnailFile != null && !mainThumbnailFile.isEmpty()) {
                String cdnUrl = uploadFileToStorage(mainThumbnailFile, contentId);
                saveImageEntity(contentId, cdnUrl, ImageType.MAIN_PAGE_THUMBNAIL);
            }

            if (popularThumbnailFile != null && !popularThumbnailFile.isEmpty()) {
                String cdnUrl = uploadFileToStorage(popularThumbnailFile, contentId);
                saveImageEntity(contentId, cdnUrl, ImageType.MAIN_PAGE_POPULAR_THUMBNAIL);
            }

            recommendBatchService.updateRecommendationsWithManualKeywords(recommendationKeywords);
        }
    }

    private String processHtmlAndUploadImages(String htmlContent, Long contentId, List<MultipartFile> contentImages) {
        Document doc = Jsoup.parseBodyFragment(htmlContent);
        Elements imgTags = doc.select("img");

        int orderIndex = 0;
        for (Element img : imgTags) {
            String originalSrc = img.attr("src");
            String dataFileIndex = img.attr("data-file-index");

            String cdnUrl = null;

            if (StringUtils.hasText(dataFileIndex) && contentImages != null) {
                try {
                    int index = Integer.parseInt(dataFileIndex);
                    if (index >= 0 && index < contentImages.size()) {
                        MultipartFile file = contentImages.get(index);
                        if (file != null && !file.isEmpty()) {
                            cdnUrl = uploadFileToStorage(file, contentId);
                        }
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid data-file-index: {}", dataFileIndex);
                }
            } else if (originalSrc.startsWith("http")) {
                cdnUrl = uploadUrlToStorage(originalSrc, contentId);
            }

            if (cdnUrl != null) {
                img.attr("src", cdnUrl);
                img.removeAttr("data-file-index");

                if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, cdnUrl, ImageType.MAIN_PAGE_BODY)) {
                    Image bodyImage = Image.of(ImageType.MAIN_PAGE_BODY, contentId, cdnUrl, orderIndex++);
                    imageRepository.save(bodyImage);
                }
            }
        }
        return doc.body().html();
    }

    private String uploadFileToStorage(MultipartFile file, Long contentId) {
        String ext = getExtension(file.getOriginalFilename());
        String key = "main-page/" + contentId + "/" + UUID.randomUUID() + ext;

        imageStorageClient.upload(file, key);

        return imageStorageClient.generatePublicUrl(key);
    }

    private String uploadUrlToStorage(String originalUrl, Long contentId) {
        String ext = getExtension(originalUrl);
        String key = "main-page/" + contentId + "/" + UUID.randomUUID() + ext;

        // ImageStorageClient에 위임
        String uploadedKey = imageStorageClient.uploadFromUrl(originalUrl, key);

        if (uploadedKey != null) {
            return imageStorageClient.generatePublicUrl(key);
        }
        return null;
    }

    private String getExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf(".")).toLowerCase();
            if (ext.contains("?")) {
                ext = ext.substring(0, ext.indexOf("?"));
            }
            return ext;
        }
        return ".jpg";
    }

    private String getExtensionFromUrl(String url) {
        int lastDotIndex = url.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < url.length() - 1) {
            String ext = url.substring(lastDotIndex).toLowerCase();
            if (ext.contains("?")) {
                ext = ext.substring(0, ext.indexOf("?"));
            }
            if (List.of(".jpg", ".jpeg", ".png", ".gif", ".webp").contains(ext)) {
                return ext;
            }
        }
        return ".jpg";
    }

    private void saveImageEntity(Long contentId, String cdnUrl, ImageType type) {
        if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, cdnUrl, type)) {
            Image image = Image.of(type, contentId, cdnUrl, 0);
            imageRepository.save(image);
        }
    }

    @Override
    @Transactional
    public void reportPost(Long reporterUserId, Long postId, PostReportRequest request) {
        User reporter = userRepository.findById(reporterUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Post reportedPost = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        if (postReportRepository.existsByReporterAndPost(reporter, reportedPost)) {
            throw new BusinessException(CommunityErrorCode.DUPLICATE_REPORT);
        }

        if (reportedPost.getAuthor().getId().equals(reporterUserId)) {
            throw new BusinessException(CommunityErrorCode.CANNOT_REPORT_SELF);
        }

        PostReport postReport = new PostReport(
                reporter,
                reportedPost.getAuthor(),
                reportedPost,
                request.reasonCategory(),
                request.reasonDetail()
        );

        postReportRepository.save(postReport);
    }

    private static final class LatestKey {
        final Instant t;
        final Long id;

        LatestKey(Instant t, Long id) {
            this.t = t;
            this.id = id;
        }
    }

    private static final class PopularKey {
        final Long sc;
        final Long id;

        PopularKey(Long sc, Long id) {
            this.sc = sc;
            this.id = id;
        }
    }
}
