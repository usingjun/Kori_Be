package core.domain.user.service.withdrawal;

import core.domain.user.entity.User;

public record UserWithdrawalContext(User user) {

    public Long userId() {
        return user.getId();
    }
}
