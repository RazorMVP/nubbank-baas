package com.nubbank.baas.engine.group;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Group {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "external_id", length = 100) private String externalId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private GroupStatus status;
    @Column(name = "office_id") private UUID officeId;
    @Column(name = "staff_id") private UUID staffId;
    @Column(name = "activation_date") private LocalDate activationDate;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = GroupStatus.PENDING;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
