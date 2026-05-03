package com.nubbank.baas.engine.loan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "loan_reschedule_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanRescheduleRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "loan_id", nullable = false) private Loan loan;
    @Column(nullable = false, length = 50) private String status;
    @Column(name = "reschedule_from_date") private LocalDate rescheduleFromDate;
    @Column(name = "new_interest_rate", precision = 8, scale = 4) private BigDecimal newInterestRate;
    @Column(name = "grace_on_principal", nullable = false) private Integer graceOnPrincipal;
    @Column(name = "grace_on_interest", nullable = false) private Integer graceOnInterest;
    @Column(name = "extra_terms", nullable = false) private Integer extraTerms;
    @Column(name = "recalculate_interest", nullable = false) private Boolean recalculateInterest;
    @Column(columnDefinition = "TEXT") private String reason;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @Version private Long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = "PENDING";
        if (graceOnPrincipal == null) graceOnPrincipal = 0;
        if (graceOnInterest == null) graceOnInterest = 0;
        if (extraTerms == null) extraTerms = 0;
        if (recalculateInterest == null) recalculateInterest = true;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
