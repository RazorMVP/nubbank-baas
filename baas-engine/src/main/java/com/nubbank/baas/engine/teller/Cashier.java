package com.nubbank.baas.engine.teller;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "cashiers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cashier {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "teller_id", nullable = false) private Teller teller;
    @Column(name = "staff_id") private UUID staffId;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "is_full_day", nullable = false) private boolean isFullDay;
    @Column(name = "start_time") private LocalTime startTime;
    @Column(name = "end_time") private LocalTime endTime;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        isFullDay = true;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
