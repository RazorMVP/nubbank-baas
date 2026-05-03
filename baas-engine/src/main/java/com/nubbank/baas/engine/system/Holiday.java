package com.nubbank.baas.engine.system;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "holidays")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Holiday {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "from_date", nullable = false) private LocalDate fromDate;
    @Column(name = "to_date", nullable = false) private LocalDate toDate;
    @Column(name = "repayment_scheduling_type", nullable = false, length = 50)
    private String repaymentSchedulingType;
    @Column(nullable = false, length = 50) private String status;
    @Column(columnDefinition = "TEXT") private String description;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = "PENDING";
        if (repaymentSchedulingType == null) repaymentSchedulingType = "NEXT_WORKING_DAY";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
