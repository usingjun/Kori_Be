package core.domain.user.service.withdrawal;

import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserDeleteCommand implements UserWithdrawalCommand {

    private final UserRepository userRepository;

    @Override
    public int order() {
        return UserWithdrawalCommandOrder.USER_DELETE;
    }

    @Override
    public void execute(UserWithdrawalContext context) {
        userRepository.delete(context.user());
    }
}
