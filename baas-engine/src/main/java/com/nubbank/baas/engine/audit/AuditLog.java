package com.nubbank.baas.engine.audit;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "entity_type", nullable = false, length = 100) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(nullable = false, length = 100) private String action;
    @Column(name = "changed_by", length = 255) private String changedBy;
    @Column(name = "old_values", columnDefinition = "TEXT") private String oldValues;
    @Column(name = "new_values", columnDefinition = "TEXT") private String newValues;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
