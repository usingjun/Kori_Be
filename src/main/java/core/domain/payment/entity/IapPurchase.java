package core.domain.payment.entity;

import core.domain.payment.dto.VerifiedStorePurchase;
import core.global.enums.DeviceType;
import core.global.enums.payment.PurchaseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "iap_purchase",
        uniqueConstraints = @UniqueConstraint(name="ux_iap_purchase_dedup", columnNames={"platform","store_tx_id"}),
        indexes = @Index(name="ix_iap_purchase_user", columnList = "user_id")
)
@Getter
@NoArgsConstructor
public class IapPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private DeviceType platform;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private IapProduct product;

    @Column(name = "store_tx_id", nullable = false, length = 256)
    private String storeTxId;     // iOS: transactionId / AND: purchaseToken

    @Column(name = "original_tx_id", length = 256)
    private String originalTxId;  // iOS

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PurchaseStatus status;

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_trial", nullable = false)
    private boolean trial = false;

    @Column(name = "is_intro", nullable = false)
    private boolean intro = false;

    @Lob
    @Column(name = "raw_json")
    private String rawJson;

    public IapPurchase(Long userId, IapProduct product, VerifiedStorePurchase purchase) {
        this.userId = userId;
        this.platform = purchase.platform();
        this.product = product;
        this.storeTxId = purchase.storeTransactionId();
        this.originalTxId = purchase.originalTransactionId();
        this.status = purchase.status();
        this.purchasedAt = purchase.purchasedAt();
        this.expiresAt = purchase.expiresAt();
        this.rawJson = purchase.rawJson();
    }
}
