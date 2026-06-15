package core.domain.payment.dto;

import core.global.enums.DeviceType;
import core.global.enums.payment.PurchaseStatus;

import java.time.Instant;

public record VerifiedStorePurchase(
        DeviceType platform,
        String storeProductId,
        String storeTransactionId,
        String idempotencyKey,
        String originalTransactionId,
        PurchaseStatus status,
        Instant purchasedAt,
        Instant expiresAt,
        String rawJson
) {
}
