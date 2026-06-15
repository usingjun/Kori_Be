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
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AndroidPurchaseVerificationStrategy implements StorePurchaseVerificationStrategy {

    private final GoogleClient googleClient;
    private final IapProductRepository productRepository;
    private final IapPurchaseRepository purchaseRepository;

    @Override
    public DeviceType supportedPlatform() {
        return DeviceType.ANDROID;
    }

    @Override
    public VerifiedStorePurchase verify(VerifyRequest request) {
        if (request.purchaseToken() == null || request.purchaseToken().isBlank()) {
            throw new IllegalArgumentException("Android: purchaseToken is required");
        }

        GooglePurchase purchase = googleClient.verify(request.productId(), request.purchaseToken());
        String storeProductId = purchase.productId() != null ? purchase.productId() : request.productId();
        return new VerifiedStorePurchase(
                DeviceType.ANDROID,
                storeProductId,
                purchase.purchaseToken(),
                request.purchaseToken(),
                null,
                purchase.status(),
                purchase.purchaseTime(),
                purchase.expiresTime(),
                purchase.raw()
        );
    }

    @Override
    public IapPurchase findOrCreatePurchase(VerifiedStorePurchase verifiedPurchase, Long userId) {
        IapProduct product = productRepository
                .findByPlatformAndStoreProductId(DeviceType.ANDROID, verifiedPurchase.storeProductId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_FOLLOW_STATUS));

        return purchaseRepository
                .findByPlatformAndStoreTxId(DeviceType.ANDROID, verifiedPurchase.idempotencyKey())
                .orElseGet(() -> new IapPurchase(userId, product, verifiedPurchase));
    }
}
