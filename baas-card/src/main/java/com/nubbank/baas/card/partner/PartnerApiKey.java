package com.nubbank.baas.card.partner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Read/update view of the engine-owned {@code public.partner_api_keys} table.
 *
 * baas-card READS the key to resolve the partner and UPDATES only
 * {@code last_used_at} (via {@code PartnerApiKeyRepository.updateLastUsed}).
 * Owned (migrated) by baas-engine. Column mapping is identical to the engine
 * entity so the SAME physical table round-trips correctly.
 */
@Entity
@Table(name = "partner_api_keys", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private PartnerOrganization organization;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 20)
    private String keyPrefix;

    @Column(length = 100)
    private String name;

    @Column(columnDefinition = "jsonb")
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerEnvironment environment;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

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
        active = true;
        if (scopes == null) scopes = "[]";
        if (tier == null) tier = PartnerTier.SANDBOX;
        if (environment == null) environment = PartnerEnvironment.SANDBOX;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
