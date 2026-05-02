package com.nubbank.baas.engine.compliance;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sanctions_screening_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SanctionsScreeningLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "entity_type", nullable = false, length = 50) private String entityType;
    @Column(name = "entity_id", nullable = false) private UUID entityId;
    @Column(name = "screen_type", nullable = false, length = 50) private String screenType;
    @Column(nullable = false, length = 50) private String result;
    @Column(columnDefinition = "TEXT") private String notes;
    @Column(nullable = false, length = 100) private String provider;
    @Column(name = "screened_at", updatable = false) private Instant screenedAt;
    @PrePersist void onCreate() {
        screenedAt = Instant.now();
        if (result == null) result = "CLEAR";
        if (provider == null) provider = "INTERNAL_STUB";
    }
}
