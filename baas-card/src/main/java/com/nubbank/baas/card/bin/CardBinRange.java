package com.nubbank.baas.card.bin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A partner-owned BIN range in the SHARED {@code public} schema.
 *
 * This is deliberately a public-schema entity (NOT per-tenant): the FEP must look
 * up which partner/schema owns a card's BIN before any tenant context is known.
 * Because {@code @Table(schema="public")} pins it to public, Hibernate reaches the
 * row regardless of the current {@code PartnerContext} (including the tenant-less
 * internal HMAC call, where {@code PartnerTenantResolver} falls back to public).
 *
 * {@code bin_start}/{@code bin_end} are stored in the FROZEN normalized 8-char form
 * (see {@code BinService.normalize}) so range matching is a plain string comparison.
 */
@Entity
@Table(schema = "public", name = "card_bin_ranges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardBinRange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bin_start", nullable = false, length = 8)
    private String binStart;

    @Column(name = "bin_end", nullable = false, length = 8)
    private String binEnd;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "schema_name", nullable = false, length = 63)
    private String schemaName;

    @Column(length = 20)
    private String scheme;

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
