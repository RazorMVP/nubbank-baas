package com.nubbank.baas.engine.deposit;

import com.nubbank.baas.engine.account.Account;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.product.DepositProduct;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "fixed_deposit_accounts")
// NO schema = "public" — TENANT table; Hibernate routes via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FixedDepositAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false) private Customer customer;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id", nullable = false) private DepositProduct product;
    @Column(name = "account_number", unique = true, nullable = false, length = 20) private String accountNumber;
    @Column(name = "deposit_amount", nullable = false, precision = 19, scale = 4) private BigDecimal depositAmount;
    @Column(name = "maturity_amount", precision = 19, scale = 4) private BigDecimal maturityAmount;
    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4) private BigDecimal interestRate;
    @Column(name = "deposit_term", nullable = false) private Integer depositTerm;
    @Column(name = "deposit_term_unit", nullable = false, length = 20) private String depositTermUnit;
    @Column(name = "deposit_date") private LocalDate depositDate;
    @Column(name = "maturity_date") private LocalDate maturityDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private FixedDepositStatus status;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "linked_account_id") private Account linkedAccount;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = FixedDepositStatus.SUBMITTED;
        if (depositTermUnit == null) depositTermUnit = "MONTHS";
        if (currencyCode == null) currencyCode = "NGN";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
