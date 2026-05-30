package com.nubbank.baas.card.partner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-mapped view of the engine-owned {@code public.partner_organizations} table.
 *
 * baas-card READS this table for partner-JWT / API-key auth; baas-engine OWNS its
 * migration. The {@code @Table} keeps {@code schema="public"} because partner
 * identity is a public (cross-tenant) concern — Hibernate does NOT route it via
 * the tenant SchemaProvider.
 *
 * Column mapping is identical to the engine entity so the SAME physical table
 * round-trips correctly.
 */
@Entity
@Table(name = "partner_organizations", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PartnerEnvironment environment;

    @Column(name = "schema_name", nullable = false, unique = true, length = 100)
    private String schemaName;

    @Column(length = 500)
    private String website;

    @Column(name = "keycloak_issuer", length = 500)
    private String keycloakIssuer;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

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
        if (status == null) status = PartnerStatus.SANDBOX;
        if (tier == null) tier = PartnerTier.SANDBOX;
        if (environment == null) environment = PartnerEnvironment.SANDBOX;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
