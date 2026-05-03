package com.nubbank.baas.engine.office;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "offices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Office {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") @JsonIgnore private Office parent;
    @Column(length = 500) private String hierarchy;
    @Column(name = "opening_date") private LocalDate openingDate;
    @Column(name = "external_id", length = 100) private String externalId;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
