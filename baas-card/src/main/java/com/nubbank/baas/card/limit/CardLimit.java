package com.nubbank.baas.card.limit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-card spending limits — one optional row per card.
 *
 * <p>TENANT entity — NO {@code @Table(schema=...)}: Hibernate routes every query to
 * the authenticated partner's schema, which IS the isolation boundary. There is
 * deliberately no partnerId column. The {@code card_id} UNIQUE constraint enforces
 * at most one limit row per card (and backstops the find-or-create upsert).
 *
 * <p>All four amounts are nullable — a null amount means "no limit" (unlimited) for
 * that dimension. Money columns are {@code NUMERIC(19,4)} → {@link BigDecimal}.
 */
@Entity
@Table(name = "card_limits", uniqueConstraints = @UniqueConstraint(columnNames = "card_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Column(name = "daily_purchase", precision = 19, scale = 4)
    private BigDecimal dailyPurchase;

    @Column(name = "daily_withdrawal", precision = 19, scale = 4)
    private BigDecimal dailyWithdrawal;

    @Column(name = "per_txn", precision = 19, scale = 4)
    private BigDecimal perTxn;

    @Column(name = "monthly", precision = 19, scale = 4)
    private BigDecimal monthly;

    @Version
    private Long version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = Instant.now();
    }
}
