package com.nubbank.baas.engine.twofa;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "two_factor_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TwoFactorToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "token_hash", nullable = false, length = 255) private String tokenHash;
    @Column(name = "delivery_method", nullable = false, length = 20) private String deliveryMethod;
    @Column(nullable = false, length = 255) private String recipient;
    @Column(nullable = false) private boolean verified;
    /** Failed verification attempts. Token is locked after MAX_ATTEMPTS (5). */
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @Column(name = "locked", nullable = false) private boolean locked;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); verified = false; locked = false; failedAttempts = 0; }
}
