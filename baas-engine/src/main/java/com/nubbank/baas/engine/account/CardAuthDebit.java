package com.nubbank.baas.engine.account;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dedupe + reversal-locator row for a card authorization. TENANT entity — Hibernate
 * routes it to the partner schema. {@code auth_key} (UNIQUE) is the cross-service
 * idempotency key {@code stan|terminalId|transmissionDateTime}; a repeat debit with the
 * same key returns the stored {@link #outcome} and moves no money.
 */
@Entity
@Table(name = "card_auth_debit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardAuthDebit {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "auth_key", nullable = false, unique = true, length = 120)
    private String authKey;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardAuthOutcome outcome;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(nullable = false)
    private boolean reversed;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
