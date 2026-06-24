package com.nubbank.baas.engine.makerchecker;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "maker_checker_config")  // tenant-schema table
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MakerCheckerConfig {

    @Id
    @Column(name = "command_type", length = 100)
    private String commandType;

    // Meaningful create-time value (a config may be created with enabled=true), so it is
    // intentionally NOT force-defaulted in @PrePersist; the primitive false matches the
    // DDL default for the seeded row.
    @Column(nullable = false)
    private boolean enabled;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
