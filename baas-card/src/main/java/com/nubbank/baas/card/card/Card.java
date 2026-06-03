package com.nubbank.baas.card.card;

import com.nubbank.baas.card.config.FieldEncryptor;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued card. TENANT entity — NO {@code @Table(schema=...)}: Hibernate routes
 * every query to the authenticated partner's schema, which IS the isolation
 * boundary, so there is deliberately no partnerId column.
 *
 * <p>PAN HANDLING (most security-sensitive surface in baas-card):
 * <ul>
 *   <li>The full PAN lives ONLY in {@code pan_encrypted} (AES-GCM via
 *       {@link FieldEncryptor} {@code @Convert}). It is never returned or logged.</li>
 *   <li>{@code pan_hash} = {@code HMAC-SHA256(app.encryption.key, full PAN)} hex — a
 *       deterministic, UNIQUE lookup column. AES-GCM cannot be queried (random IV),
 *       so this is how Task 6's authorize resolves a card from a PAN.</li>
 *   <li>{@code bin} (first 8 of PAN) + {@code panLast4} drive {@link #maskedPan()},
 *       the ONLY PAN form ever exposed in a response.</li>
 * </ul>
 */
@Entity
@Table(name = "cards")
// NO schema = "public" — this is a TENANT table; Hibernate routes via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** Partner-supplied external customer reference (opaque to baas-card). */
    @Column(name = "customer_ref", length = 100)
    private String customerRef;

    /** Full PAN — AES-GCM at rest. NEVER returned or logged. */
    @Convert(converter = FieldEncryptor.class)
    @Column(name = "pan_encrypted", nullable = false, length = 500)
    private String panEncrypted;

    /** HMAC-SHA256 hex of the full PAN — deterministic, UNIQUE lookup key. */
    @Column(name = "pan_hash", nullable = false, unique = true, length = 64)
    private String panHash;

    @Column(name = "pan_last4", nullable = false, length = 4)
    private String panLast4;

    /** First 8 digits of the PAN (the card's BIN). */
    @Column(name = "bin", nullable = false, length = 8)
    private String bin;

    /** Expiry in {@code YYMM} form. */
    @Column(name = "expiry_ym", nullable = false, length = 4)
    private String expiryYm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(nullable = false)
    private boolean virtual;

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
        if (status == null) {
            status = CardStatus.ISSUED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * The ONLY PAN form ever exposed. Masks the middle digits — first 6 (the BIN)
     * and the last 4 are shown, e.g. {@code "506000******1234"}.
     */
    public String maskedPan() {
        return bin.substring(0, 6) + "******" + panLast4;
    }
}
