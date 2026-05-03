package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.account.Account;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.product.LoanProduct;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "loans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Loan {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false) private Customer customer;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "loan_product_id", nullable = false) private LoanProduct loanProduct;
    @Column(name = "loan_account_number", unique = true, nullable = false, length = 20) private String loanAccountNumber;
    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4) private BigDecimal principalAmount;
    @Column(name = "approved_principal", precision = 19, scale = 4) private BigDecimal approvedPrincipal;
    @Column(name = "outstanding_balance", nullable = false, precision = 19, scale = 4) private BigDecimal outstandingBalance;
    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4) private BigDecimal interestRate;
    @Column(name = "number_of_repayments", nullable = false) private Integer numberOfRepayments;
    @Column(name = "repayment_every", nullable = false) private Integer repaymentEvery;
    @Column(name = "repayment_frequency", nullable = false, length = 20) private String repaymentFrequency;
    @Column(name = "disbursement_date") private LocalDate disbursementDate;
    @Column(name = "expected_disbursement_date") private LocalDate expectedDisbursementDate;
    @Column(name = "maturity_date") private LocalDate maturityDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private LoanStatus status;
    @Column(name = "approved_by", length = 255) private String approvedBy;
    @Column(name = "approved_on") private Instant approvedOn;
    @Column(name = "rejected_on") private Instant rejectedOn;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "disbursed_by", length = 255) private String disbursedBy;
    @Column(name = "disbursed_on") private Instant disbursedOn;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "linked_account_id") private Account linkedAccount;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = LoanStatus.SUBMITTED;
        if (outstandingBalance == null) outstandingBalance = BigDecimal.ZERO;
        if (currencyCode == null) currencyCode = "NGN";
        if (repaymentEvery == null) repaymentEvery = 1;
        if (repaymentFrequency == null) repaymentFrequency = "MONTHS";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
