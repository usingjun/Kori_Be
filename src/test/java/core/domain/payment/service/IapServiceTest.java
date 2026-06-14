package core.domain.payment.service;

import core.domain.payment.dto.EntitlementResponse;
import core.domain.payment.dto.VerifiedStorePurchase;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.IapBonusGrant;
import core.domain.payment.entity.IapEntitlement;
import core.domain.payment.entity.IapProduct;
import core.domain.payment.entity.IapPurchase;
import core.domain.payment.entity.UserItem;
import core.domain.payment.repository.IapBonusGrantRepository;
import core.domain.payment.repository.IapEntitlementRepository;
import core.domain.payment.repository.IapPurchaseRepository;
import core.domain.payment.repository.UserItemRepository;
import core.domain.payment.service.strategy.StorePurchaseVerificationStrategy;
import core.domain.payment.service.strategy.StorePurchaseVerificationStrategyResolver;
import core.domain.user.entity.User;
import core.domain.user.entity.UserTestBuilder;
import core.domain.user.repository.UserRepository;
import core.global.enums.DeviceType;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.payment.PaymentProductType;
import core.global.enums.payment.PurchaseStatus;
import core.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IapServiceTest {

    @Mock StorePurchaseVerificationStrategyResolver strategyResolver;
    @Mock StorePurchaseVerificationStrategy strategy;
    @Mock IapPurchaseRepository purchaseRepository;
    @Mock IapEntitlementRepository entitlementRepository;
    @Mock UserItemRepository userItemRepository;
    @Mock IapBonusGrantRepository bonusGrantRepository;
    @Mock UserRepository userRepository;

    @InjectMocks IapService iapService;

    private final VerifyRequest request = new VerifyRequest("ios", "tx", null, null);
    private final VerifiedStorePurchase verifiedPurchase = new VerifiedStorePurchase(
            DeviceType.IOS,
            "premium",
            "tx",
            "tx",
            "original-tx",
            PurchaseStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"),
            "{}"
    );

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "password")
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void verify_callsStoreVerificationBeforeUserLookup() {
        when(strategyResolver.resolve("ios")).thenReturn(strategy);
        when(strategy.verify(request)).thenReturn(verifiedPurchase);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> iapService.verify(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND));

        InOrder order = inOrder(strategyResolver, strategy, userRepository);
        order.verify(strategyResolver).resolve("ios");
        order.verify(strategy).verify(request);
        order.verify(userRepository).findByEmail("user@example.com");
        verify(strategy, never()).findOrCreatePurchase(any(), any());
    }

    @Test
    void verify_savesPurchaseEntitlementAndReturnsResponse() {
        User user = UserTestBuilder.builder().id(10L).email("user@example.com").build();
        IapProduct product = product("premium", PaymentProductType.SUBS, "pro", "premium");
        IapPurchase purchase = new IapPurchase(10L, product, verifiedPurchase);
        stubSuccessfulVerification(user, purchase);
        when(bonusGrantRepository.findByUserIdAndBonusCode(10L, "welcome_frame"))
                .thenReturn(Optional.of(new IapBonusGrant(purchase, "welcome_frame")));

        EntitlementResponse response = iapService.verify(request);

        verify(purchaseRepository).save(purchase);
        verify(entitlementRepository).save(any(IapEntitlement.class));
        assertThat(response.userId()).isEqualTo(10L);
        assertThat(response.active()).isTrue();
        assertThat(response.feature()).isEqualTo("pro");
        assertThat(response.tier()).isEqualTo("premium");
        assertThat(response.source()).isEqualTo("IOS");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void verify_incrementsBoostForActiveInAppPurchase() {
        User user = UserTestBuilder.builder().id(10L).email("user@example.com").build();
        IapProduct product = product("boost_profile", PaymentProductType.INAPP, "boost", "single");
        IapPurchase purchase = new IapPurchase(10L, product, verifiedPurchase);
        UserItem boost = new UserItem(10L, "boost", 2);
        stubSuccessfulVerification(user, purchase);
        when(userItemRepository.findByUserIdAndItemCode(10L, "boost")).thenReturn(Optional.of(boost));

        iapService.verify(request);

        assertThat(boost.getQuantity()).isEqualTo(3);
        verify(userItemRepository).save(boost);
        verify(bonusGrantRepository, never()).save(any());
    }

    @Test
    void verify_grantsWelcomeFrameOnlyOnce() {
        User user = UserTestBuilder.builder().id(10L).email("user@example.com").build();
        IapProduct product = product("premium_monthly", PaymentProductType.SUBS, "pro", "premium");
        IapPurchase purchase = new IapPurchase(10L, product, verifiedPurchase);
        UserItem frame = new UserItem(10L, "frame", 0);
        stubSuccessfulVerification(user, purchase);
        when(bonusGrantRepository.findByUserIdAndBonusCode(10L, "welcome_frame"))
                .thenReturn(Optional.empty());
        when(userItemRepository.findByUserIdAndItemCode(10L, "frame")).thenReturn(Optional.of(frame));

        iapService.verify(request);

        assertThat(frame.getQuantity()).isEqualTo(1);
        verify(userItemRepository).save(frame);
        verify(bonusGrantRepository).save(any(IapBonusGrant.class));
    }

    private void stubSuccessfulVerification(User user, IapPurchase purchase) {
        when(strategyResolver.resolve("ios")).thenReturn(strategy);
        when(strategy.verify(request)).thenReturn(verifiedPurchase);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(strategy.findOrCreatePurchase(verifiedPurchase, user.getId())).thenReturn(purchase);
    }

    private IapProduct product(
            String storeProductId,
            PaymentProductType type,
            String feature,
            String tier
    ) {
        IapProduct product = new IapProduct();
        ReflectionTestUtils.setField(product, "platform", DeviceType.IOS);
        ReflectionTestUtils.setField(product, "storeProductId", storeProductId);
        ReflectionTestUtils.setField(product, "type", type);
        ReflectionTestUtils.setField(product, "feature", feature);
        ReflectionTestUtils.setField(product, "tier", tier);
        return product;
    }
}
