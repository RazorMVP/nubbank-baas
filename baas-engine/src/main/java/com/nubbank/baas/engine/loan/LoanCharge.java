package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.charge.Charge;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "loan_charges")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanCharge {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "loan_id", nullable = false) private Loan loan;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "charge_id", nullable = false) private Charge charge;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "amount_paid", nullable = false, precision = 19, scale = 4) private BigDecimal amountPaid;
    @Column(nullable = false) private boolean waived;
    @Column(name = "due_date") private LocalDate dueDate;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); if (amountPaid == null) amountPaid = BigDecimal.ZERO; }
}
