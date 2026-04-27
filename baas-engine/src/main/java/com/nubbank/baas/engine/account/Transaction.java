package com.nubbank.baas.engine.account;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
// NO schema annotation — TENANT table
// NO @Version — transactions are append-only, never updated
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private TransactionType transactionType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "running_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal runningBalance;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(length = 100)
    private String reference;

    @Column(length = 500)
    private String description;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (currencyCode == null) currencyCode = "NGN";
    }
}
