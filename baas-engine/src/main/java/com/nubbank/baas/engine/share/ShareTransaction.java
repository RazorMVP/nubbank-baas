package com.nubbank.baas.engine.share;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShareTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private ShareAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private ShareTransactionType transactionType;

    @Column(name = "number_of_shares", nullable = false)
    private Long numberOfShares;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (unitPrice != null && numberOfShares != null) {
            totalAmount = unitPrice.multiply(BigDecimal.valueOf(numberOfShares));
        }
    }
}
