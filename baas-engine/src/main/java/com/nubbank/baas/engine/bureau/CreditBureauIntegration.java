package com.nubbank.baas.engine.bureau;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credit_bureau_integrations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditBureauIntegration {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "impl_class", nullable = false, length = 500) private String implClass;
    @Column(length = 3) private String country;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); active = false; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
