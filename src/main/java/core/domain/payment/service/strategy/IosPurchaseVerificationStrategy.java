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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IosPurchaseVerificationStrategy implements StorePurchaseVerificationStrategy {

    private final AppleClient appleClient;
    private final IapProductRepository productRepository;
    private final IapPurchaseRepository purchaseRepository;

    @Override
    public DeviceType supportedPlatform() {
        return DeviceType.IOS;
    }

    @Override
    public VerifiedStorePurchase verify(VerifyRequest request) {
        if (request.transactionId() == null || request.transactionId().isBlank()) {
            throw new IllegalArgumentException("iOS: transactionId is required");
        }

        AppleTransactionInfo transaction = appleClient.getTransaction(request.transactionId());
        return new VerifiedStorePurchase(
                DeviceType.IOS,
                transaction.productId(),
                transaction.transactionId(),
                transaction.transactionId(),
                transaction.originalTransactionId(),
                transaction.status(),
                transaction.purchaseDate(),
                transaction.expiresDate(),
                transaction.raw()
        );
    }

    @Override
    public IapPurchase findOrCreatePurchase(VerifiedStorePurchase verifiedPurchase, Long userId) {
        IapProduct product = productRepository
                .findByPlatformAndStoreProductId(DeviceType.IOS, verifiedPurchase.storeProductId())
                .orElse(null);

        return purchaseRepository
                .findByPlatformAndStoreTxId(DeviceType.IOS, verifiedPurchase.idempotencyKey())
                .orElseGet(() -> new IapPurchase(userId, product, verifiedPurchase));
    }
}
