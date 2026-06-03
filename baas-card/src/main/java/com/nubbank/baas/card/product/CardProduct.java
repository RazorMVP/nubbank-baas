package com.nubbank.baas.card.product;

import com.nubbank.baas.card.card.CardType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A partner-defined card product template (Virtual Debit, Premium Credit, etc.).
 *
 * TENANT entity — NO {@code @Table(schema=...)}. Hibernate routes every query to
 * the authenticated partner's {@code partner_{uuid}} schema via
 * {@code PartnerTenantResolver}, so the schema itself is the isolation boundary.
 * There is deliberately NO partnerId column: a row is reachable only from its own
 * partner's schema.
 */
@Entity
@Table(name = "card_products")
// NO schema = "public" — this is a TENANT table; Hibernate routes via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CardProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, length = 20)
    private CardType cardType;

    /** ISO 4217 alphabetic currency code (e.g. {@code NGN}). */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Optional link to a registered BIN (8-char normalized form). */
    @Column(name = "bin_start", length = 8)
    private String binStart;

    @Column(name = "default_daily_limit", precision = 19, scale = 4)
    private BigDecimal defaultDailyLimit;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        active = true;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
