package core.domain.payment.service.strategy;

import core.domain.payment.config.AppleClient;
import core.domain.payment.dto.AppleTransactionInfo;
import core.domain.payment.dto.VerifiedStorePurchase;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.IapProduct;
import core.domain.payment.entity.IapPurchase;
import core.domain.payment.repository.IapProductRepository;
import core.domain.payment.repository.IapPurchaseRepository;
import core.global.enums.DeviceType;
import core.global.enums.payment.PurchaseStatus;
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
class IosPurchaseVerificationStrategyTest {

    @Mock AppleClient appleClient;
    @Mock IapProductRepository productRepository;
    @Mock IapPurchaseRepository purchaseRepository;

    @InjectMocks IosPurchaseVerificationStrategy strategy;

    @Test
    void verify_requiresTransactionId() {
        assertThatThrownBy(() -> strategy.verify(new VerifyRequest("ios", " ", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("iOS: transactionId is required");

        verify(appleClient, never()).getTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verify_normalizesAppleTransaction() {
        Instant purchasedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-02-01T00:00:00Z");
        AppleTransactionInfo transaction = new AppleTransactionInfo(
                "apple-tx", "apple-original", "premium", purchasedAt, expiresAt,
                PurchaseStatus.ACTIVE, "{\"store\":\"apple\"}"
        );
        when(appleClient.getTransaction("request-tx")).thenReturn(transaction);

        VerifiedStorePurchase result =
                strategy.verify(new VerifyRequest("ios", "request-tx", null, null));

        assertThat(result.platform()).isEqualTo(DeviceType.IOS);
        assertThat(result.storeTransactionId()).isEqualTo("apple-tx");
        assertThat(result.idempotencyKey()).isEqualTo("apple-tx");
        assertThat(result.originalTransactionId()).isEqualTo("apple-original");
        assertThat(result.storeProductId()).isEqualTo("premium");
        assertThat(result.status()).isEqualTo(PurchaseStatus.ACTIVE);
        assertThat(result.purchasedAt()).isEqualTo(purchasedAt);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        assertThat(result.rawJson()).isEqualTo("{\"store\":\"apple\"}");
    }

    @Test
    void findOrCreatePurchase_returnsExistingPurchaseUsingAppleTransactionId() {
        VerifiedStorePurchase verified = verifiedPurchase();
        IapPurchase existing = new IapPurchase(1L, null, verified);
        when(productRepository.findByPlatformAndStoreProductId(DeviceType.IOS, "premium"))
                .thenReturn(Optional.empty());
        when(purchaseRepository.findByPlatformAndStoreTxId(DeviceType.IOS, "apple-tx"))
                .thenReturn(Optional.of(existing));

        IapPurchase result = strategy.findOrCreatePurchase(verified, 99L);

        assertThat(result).isSameAs(existing);
        verify(purchaseRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findOrCreatePurchase_allowsMissingProductAndCreatesPurchase() {
        VerifiedStorePurchase verified = verifiedPurchase();
        when(productRepository.findByPlatformAndStoreProductId(DeviceType.IOS, "premium"))
                .thenReturn(Optional.empty());
        when(purchaseRepository.findByPlatformAndStoreTxId(DeviceType.IOS, "apple-tx"))
                .thenReturn(Optional.empty());

        IapPurchase result = strategy.findOrCreatePurchase(verified, 99L);

        assertThat(result.getUserId()).isEqualTo(99L);
        assertThat(result.getPlatform()).isEqualTo(DeviceType.IOS);
        assertThat(result.getProduct()).isNull();
        assertThat(result.getStoreTxId()).isEqualTo("apple-tx");
        assertThat(result.getOriginalTxId()).isEqualTo("apple-original");
        assertThat(result.getRawJson()).isEqualTo("{\"store\":\"apple\"}");
    }

    private VerifiedStorePurchase verifiedPurchase() {
        return new VerifiedStorePurchase(
                DeviceType.IOS,
                "premium",
                "apple-tx",
                "apple-tx",
                "apple-original",
                PurchaseStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "{\"store\":\"apple\"}"
        );
    }
}
