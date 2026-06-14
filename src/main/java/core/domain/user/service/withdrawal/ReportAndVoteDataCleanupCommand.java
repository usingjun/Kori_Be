package core.domain.user.service.withdrawal;

import core.domain.chat.repository.ChatReportRepository;
import core.domain.poll.repository.VoteRecordRepository;
import core.domain.post.repository.PostReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportAndVoteDataCleanupCommand implements UserWithdrawalCommand {

    private final PostReportRepository postReportRepository;
    private final ChatReportRepository chatReportRepository;
    private final VoteRecordRepository voteRecordRepository;

    @Override
    public int order() {
        return UserWithdrawalCommandOrder.REPORT_AND_VOTE_DATA;
    }

    @Override
    public void execute(UserWithdrawalContext context) {
        Long userId = context.userId();
        postReportRepository.deleteAllByReporterId(userId);
        chatReportRepository.deleteAllByReporterUserId(userId);
        postReportRepository.deleteAllByReportedUserId(userId);
        chatReportRepository.deleteAllByReportedUserId(userId);
        voteRecordRepository.deleteAllByUserId(userId);
    }
}
