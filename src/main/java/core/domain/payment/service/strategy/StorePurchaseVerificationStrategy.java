package core.domain.payment.service.strategy;

import core.domain.payment.dto.VerifiedStorePurchase;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.IapPurchase;
import core.global.enums.DeviceType;

public interface StorePurchaseVerificationStrategy {

    DeviceType supportedPlatform();

    VerifiedStorePurchase verify(VerifyRequest request);

    IapPurchase findOrCreatePurchase(VerifiedStorePurchase verifiedPurchase, Long userId);
}
