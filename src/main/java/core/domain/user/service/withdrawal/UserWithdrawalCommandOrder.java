package core.domain.user.service.withdrawal;

public final class UserWithdrawalCommandOrder {

    public static final int PAYMENT_DATA = 100;
    public static final int REPORT_AND_VOTE_DATA = 200;
    public static final int CHAT_DATA = 300;
    public static final int COMMUNITY_DATA = 400;
    public static final int USER_OWNED_DATA = 500;
    public static final int USER_DELETE = 1000;

    private UserWithdrawalCommandOrder() {
    }
}
