package com.nubbank.baas.engine.accounting;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "gl_closures")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlClosure {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "closing_date", unique = true, nullable = false) private LocalDate closingDate;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "closed_by", length = 255) private String closedBy;
    @Column(name = "closed_at", updatable = false) private Instant closedAt;
    @PrePersist void onCreate() { closedAt = Instant.now(); }
}
