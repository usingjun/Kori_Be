package core.domain.user.service.withdrawal;

public interface UserWithdrawalCommand {

    int order();

    void execute(UserWithdrawalContext context);
}
