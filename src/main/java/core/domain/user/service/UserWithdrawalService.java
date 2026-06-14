package core.domain.user.service;

import core.domain.user.dto.UserWithdrawalEvent;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.withdrawal.UserWithdrawalCommand;
import core.domain.user.service.withdrawal.UserWithdrawalContext;
import core.global.apple.service.AppleWithdrawalService;
import core.global.enums.Oauthplatform;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Comparator;

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
    private final List<UserWithdrawalCommand> withdrawalCommands;

    @Transactional
    public boolean withdraw(Long userId, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        boolean appleAccount = Oauthplatform.APPLE.toString().equals(user.getProvider());
        if (appleAccount) {
            appleWithdrawalService.revokeAppleToken(user);
        }

        UserWithdrawalContext context = new UserWithdrawalContext(user);
        withdrawalCommands.stream()
                .sorted(Comparator.comparingInt(UserWithdrawalCommand::order))
                .forEach(command -> command.execute(context));

        eventPublisher.publishEvent(new UserWithdrawalEvent(userId, accessToken));
        return appleAccount;
    }
}
