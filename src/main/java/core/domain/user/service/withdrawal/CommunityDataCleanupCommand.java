package core.domain.user.service.withdrawal;

import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.poll.entity.Poll;
import core.domain.poll.repository.VoteRecordRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostReportRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.repository.FollowRepository;
import core.global.entity.like.repository.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class CommunityDataCleanupCommand implements UserWithdrawalCommand {

    private final BlockPostRepository blockPostRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final PostReportRepository postReportRepository;
    private final VoteRecordRepository voteRecordRepository;
    private final LikeRepository likeRepository;
    private final FollowRepository followRepository;

    @Override
    public int order() {
        return UserWithdrawalCommandOrder.COMMUNITY_DATA;
    }

    @Override
    public void execute(UserWithdrawalContext context) {
        Long userId = context.userId();
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
}
