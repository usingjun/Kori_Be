package core.domain.payment.service.strategy;

import core.domain.payment.config.GoogleClient;
import core.domain.payment.dto.GooglePurchase;
import core.domain.payment.dto.VerifiedStorePurchase;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.IapProduct;
import core.domain.payment.entity.IapPurchase;
import core.domain.payment.repository.IapProductRepository;
import core.domain.payment.repository.IapPurchaseRepository;
import core.global.enums.DeviceType;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.payment.PurchaseStatus;
import core.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidPurchaseVerificationStrategyTest {

    @Mock GoogleClient googleClient;
    @Mock IapProductRepository productRepository;
    @Mock IapPurchaseRepository purchaseRepository;

    @InjectMocks AndroidPurchaseVerificationStrategy strategy;

    @Test
    void verify_requiresPurchaseToken() {
        assertThatThrownBy(() -> strategy.verify(new VerifyRequest("android", null, " ", "premium")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Android: purchaseToken is required");

        verify(googleClient, never()).verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verify_prefersGoogleProductId() {
        when(googleClient.verify("request-product", "token"))
                .thenReturn(googlePurchase("google-product"));

        VerifiedStorePurchase result =
                strategy.verify(new VerifyRequest("android", null, "token", "request-product"));

        assertThat(result.platform()).isEqualTo(DeviceType.ANDROID);
        assertThat(result.storeProductId()).isEqualTo("google-product");
        assertThat(result.storeTransactionId()).isEqualTo("google-response-token");
        assertThat(result.idempotencyKey()).isEqualTo("token");
        assertThat(result.originalTransactionId()).isNull();
    }

    @Test
    void verify_fallsBackToRequestProductId() {
        when(googleClient.verify("request-product", "token")).thenReturn(googlePurchase(null));

        VerifiedStorePurchase result =
                strategy.verify(new VerifyRequest("android", null, "token", "request-product"));

        assertThat(result.storeProductId()).isEqualTo("request-product");
    }

    @Test
    void findOrCreatePurchase_keepsExistingMissingProductError() {
        VerifiedStorePurchase verified = verifiedPurchase();
        when(productRepository.findByPlatformAndStoreProductId(DeviceType.ANDROID, "premium"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.findOrCreatePurchase(verified, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getError()).isEqualTo(UserErrorCode.INVALID_FOLLOW_STATUS));
    }

    @Test
    void findOrCreatePurchase_returnsExistingPurchaseUsingPurchaseToken() {
        VerifiedStorePurchase verified = verifiedPurchase();
        IapProduct product = new IapProduct();
        IapPurchase existing = new IapPurchase(1L, product, verified);
        when(productRepository.findByPlatformAndStoreProductId(DeviceType.ANDROID, "premium"))
                .thenReturn(Optional.of(product));
        when(purchaseRepository.findByPlatformAndStoreTxId(DeviceType.ANDROID, "token"))
                .thenReturn(Optional.of(existing));

        IapPurchase result = strategy.findOrCreatePurchase(verified, 99L);

        assertThat(result).isSameAs(existing);
        verify(purchaseRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findOrCreatePurchase_createsAndroidPurchase() {
        VerifiedStorePurchase verified = verifiedPurchase();
        IapProduct product = new IapProduct();
        when(productRepository.findByPlatformAndStoreProductId(DeviceType.ANDROID, "premium"))
                .thenReturn(Optional.of(product));
        when(purchaseRepository.findByPlatformAndStoreTxId(DeviceType.ANDROID, "token"))
                .thenReturn(Optional.empty());

        IapPurchase result = strategy.findOrCreatePurchase(verified, 99L);

        assertThat(result.getUserId()).isEqualTo(99L);
        assertThat(result.getPlatform()).isEqualTo(DeviceType.ANDROID);
        assertThat(result.getProduct()).isSameAs(product);
        assertThat(result.getStoreTxId()).isEqualTo("google-response-token");
        assertThat(result.getOriginalTxId()).isNull();
        assertThat(result.getRawJson()).isEqualTo("{\"store\":\"google\"}");
    }

    private GooglePurchase googlePurchase(String productId) {
        return new GooglePurchase(
                productId,
                "google-response-token",
                PurchaseStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "{\"store\":\"google\"}"
        );
    }

    private VerifiedStorePurchase verifiedPurchase() {
        return new VerifiedStorePurchase(
                DeviceType.ANDROID,
                "premium",
                "google-response-token",
                "token",
                null,
                PurchaseStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "{\"store\":\"google\"}"
        );
    }
}
