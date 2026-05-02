package com.nubbank.baas.engine.rate;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "floating_rates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FloatingRate {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @JsonProperty("isBaseLendingRate")
    @Column(name = "is_base_lending_rate", nullable = false) private boolean baseLendingRate;
    @JsonProperty("isActive")
    @Column(name = "is_active", nullable = false) private boolean active;
    @Builder.Default
    @OneToMany(mappedBy = "floatingRate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FloatingRatePeriod> periods = new ArrayList<>();
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); active = true; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
