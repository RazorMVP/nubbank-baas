package com.nubbank.baas.engine.loan;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "loan_repayment_schedule")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanRepaymentSchedule {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "loan_id", nullable = false) private Loan loan;
    @Column(name = "installment_no", nullable = false) private Integer installmentNo;
    @Column(name = "due_date", nullable = false) private LocalDate dueDate;
    @Column(name = "principal_due", nullable = false, precision = 19, scale = 4) private BigDecimal principalDue;
    @Column(name = "interest_due", nullable = false, precision = 19, scale = 4) private BigDecimal interestDue;
    @Column(name = "total_due", nullable = false, precision = 19, scale = 4) private BigDecimal totalDue;
    @Column(name = "principal_paid", nullable = false, precision = 19, scale = 4) private BigDecimal principalPaid;
    @Column(name = "interest_paid", nullable = false, precision = 19, scale = 4) private BigDecimal interestPaid;
    @Column(name = "total_paid", nullable = false, precision = 19, scale = 4) private BigDecimal totalPaid;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private RepaymentStatus status;
    @Column(name = "completed_on") private LocalDate completedOn;
    @Column(name = "created_at", updatable = false) private Instant createdAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now();
        if (status == null) status = RepaymentStatus.PENDING;
        if (principalPaid == null) principalPaid = BigDecimal.ZERO;
        if (interestPaid == null) interestPaid = BigDecimal.ZERO;
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;
    }
}
