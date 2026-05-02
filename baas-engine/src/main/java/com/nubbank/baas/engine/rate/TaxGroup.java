package com.nubbank.baas.engine.rate;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "tax_groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxGroup {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Builder.Default
    @OneToMany(mappedBy = "taxGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaxGroupMapping> mappings = new ArrayList<>();
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
