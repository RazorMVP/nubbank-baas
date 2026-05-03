package com.nubbank.baas.engine.system;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "codes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Code {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false, length = 100) private String name;
    @Column(name = "system_defined", nullable = false) private boolean systemDefined;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
