package core.domain.payment.service;

import core.domain.payment.dto.EntitlementResponse;
import core.domain.payment.dto.VerifiedStorePurchase;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.*;
import core.domain.payment.repository.*;
import core.domain.payment.service.strategy.StorePurchaseVerificationStrategy;
import core.domain.payment.service.strategy.StorePurchaseVerificationStrategyResolver;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.payment.EntitlementStatus;
import core.global.enums.payment.PaymentProductType;
import core.global.enums.payment.PurchaseStatus;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IapService {

    private final StorePurchaseVerificationStrategyResolver strategyResolver;
    private final IapPurchaseRepository purchaseRepository;
    private final IapEntitlementRepository entitlementRepository;
    private final UserItemRepository userItemRepository;
    private final IapBonusGrantRepository bonusGrantRepository;
    private final UserRepository userRepository;

    @Transactional
    public EntitlementResponse verify(VerifyRequest req) {
        StorePurchaseVerificationStrategy strategy = strategyResolver.resolve(req.platform());
        VerifiedStorePurchase verifiedPurchase = strategy.verify(req);

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        IapPurchase purchase = strategy.findOrCreatePurchase(verifiedPurchase, user.getId());
        purchaseRepository.save(purchase);
        applyPostPurchaseSideEffects(purchase);

        IapEntitlement entitlement = IapEntitlement.fromPurchase(purchase);
        entitlementRepository.save(entitlement);

        return EntitlementResponse.fromEntity(entitlement);
    }

    public EntitlementResponse getEntitlements() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        IapEntitlement e = entitlementRepository.findTopByUserIdOrderByUpdatedAtDesc(user.getId())
                .orElseGet(() -> new IapEntitlement(user.getId(), false, EntitlementStatus.EXPIRED));
        return EntitlementResponse.fromEntity(e);
    }

    private void applyPostPurchaseSideEffects(IapPurchase purchase) {
        applyConsumableCreditIfBoostPurchase(purchase);
        grantWelcomeFrameIfFirstPremiumActivation(purchase);
    }

    private void applyConsumableCreditIfBoostPurchase(IapPurchase purchase) {
        boolean isBoost = purchase.getProduct() != null
                          && purchase.getProduct().getType() == PaymentProductType.INAPP
                          && "boost_profile".equals(purchase.getProduct().getStoreProductId());

        if (isBoost && purchase.getStatus() == PurchaseStatus.ACTIVE) {
            incrementUserItemQuantity(purchase.getUserId(), "boost", 1);
        }
    }

    private void grantWelcomeFrameIfFirstPremiumActivation(IapPurchase purchase) {
        boolean isPremiumSubscription = purchase.getProduct() != null
                                        && purchase.getProduct().getType() == PaymentProductType.SUBS
                                        && "premium".equalsIgnoreCase(purchase.getProduct().getTier());

        if (!isPremiumSubscription) return;
        if (purchase.getStatus() != PurchaseStatus.ACTIVE) return;

        boolean alreadyGranted = bonusGrantRepository
                .findByUserIdAndBonusCode(purchase.getUserId(), "welcome_frame")
                .isPresent();

        if (alreadyGranted) return;

        incrementUserItemQuantity(purchase.getUserId(), "frame", 1);

        IapBonusGrant grant = new IapBonusGrant(purchase, "welcome_frame");
        bonusGrantRepository.save(grant);
    }

    private void incrementUserItemQuantity(Long userId, String itemCode, int delta) {
        UserItem item = userItemRepository
                .findByUserIdAndItemCode(userId, itemCode)
                .orElseGet(() -> new UserItem(userId, itemCode, 0));
        item.updateQuantity(Math.max(0, item.getQuantity() + delta));
        userItemRepository.save(item);
    }

}
