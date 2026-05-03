package com.nubbank.baas.engine.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maker_checker_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MakerCheckerRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "entity_type", nullable = false, length = 100) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(nullable = false, length = 100) private String action;
    @Column(name = "command_as_json", columnDefinition = "TEXT", nullable = false) private String commandAsJson;
    @Column(nullable = false, length = 50) private String status;
    @Column(name = "made_by_user_id", nullable = false) private UUID madeByUserId;
    @Column(name = "checked_by_user_id") private UUID checkedByUserId;
    @Column(name = "rejection_reason", columnDefinition = "TEXT") private String rejectionReason;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = "PENDING";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
