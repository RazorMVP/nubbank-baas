package com.nubbank.baas.engine.share;

import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShareAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private ShareProduct product;

    @Column(name = "account_number", unique = true, nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "total_shares_held", nullable = false)
    private Long totalSharesHeld;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ShareAccountStatus status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = ShareAccountStatus.SUBMITTED;
        if (totalSharesHeld == null) totalSharesHeld = 0L;
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;
        if (currencyCode == null) currencyCode = "NGN";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
