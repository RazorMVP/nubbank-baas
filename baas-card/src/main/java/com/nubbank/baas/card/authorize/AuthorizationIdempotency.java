package com.nubbank.baas.card.authorize;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization idempotency record (F3). TENANT entity — NO {@code @Table(schema=...)};
 * Hibernate routes it to the partner schema set by {@link com.nubbank.baas.card.tenant.PartnerContext}.
 *
 * <p>{@code idemKey = stan | terminalId | transmissionDateTime} (ISO DE11/DE41/DE7).
 * A retransmit with the same key returns the cached decision instead of re-deciding.
 * {@code reversed} is flipped by the reversal endpoint (F6). Retention: a daily purge
 * (see {@link IdempotencyPurgeJob}) deletes rows older than 24h.
 */
@Entity
@Table(name = "authorization_idempotency",
       uniqueConstraints = @UniqueConstraint(columnNames = "idem_key"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuthorizationIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idem_key", nullable = false, unique = true, length = 120)
    private String idemKey;

    @Column(nullable = false, length = 10)
    private String decision;

    @Column(name = "response_code", nullable = false, length = 2)
    private String responseCode;

    @Column(length = 255)
    private String message;

    @Column(nullable = false)
    private boolean reversed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
