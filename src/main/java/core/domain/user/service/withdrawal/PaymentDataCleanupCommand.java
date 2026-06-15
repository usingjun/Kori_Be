package core.domain.user.service.withdrawal;

import core.domain.payment.repository.IapBonusGrantRepository;
import core.domain.payment.repository.IapEntitlementRepository;
import core.domain.payment.repository.IapPurchaseRepository;
import core.domain.payment.repository.UserItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentDataCleanupCommand implements UserWithdrawalCommand {

    private final IapEntitlementRepository iapEntitlementRepository;
    private final IapBonusGrantRepository iapBonusGrantRepository;
    private final UserItemRepository userItemRepository;
    private final IapPurchaseRepository iapPurchaseRepository;

    @Override
    public int order() {
        return UserWithdrawalCommandOrder.PAYMENT_DATA;
    }

    @Override
    public void execute(UserWithdrawalContext context) {
        Long userId = context.userId();
        iapEntitlementRepository.deleteAllByUserId(userId);
        iapBonusGrantRepository.deleteAllByUserId(userId);
        userItemRepository.deleteAllByUserId(userId);
        iapPurchaseRepository.deleteAllByUserId(userId);
    }
}
