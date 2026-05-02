package com.nubbank.baas.engine.system;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "system_configurations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SystemConfiguration {
    @Id
    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;
    @Column(columnDefinition = "TEXT") private String value;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { updatedAt = Instant.now(); enabled = true; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
