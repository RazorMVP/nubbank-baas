package com.nubbank.baas.engine.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entity_notes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EntityNote {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "entity_type", nullable = false, length = 100) private String entityType;
    @Column(name = "entity_id", nullable = false) private UUID entityId;
    @Column(columnDefinition = "TEXT", nullable = false) private String note;
    @Column(name = "created_by", length = 255) private String createdBy;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
