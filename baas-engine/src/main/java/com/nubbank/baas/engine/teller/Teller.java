package com.nubbank.baas.engine.teller;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tellers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Teller {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private TellerStatus status;
    @Column(name = "office_id") private UUID officeId;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = TellerStatus.INACTIVE;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
