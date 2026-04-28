package com.nubbank.baas.engine.product;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deposit_products")
// NO schema = "public" — this is a TENANT table; Hibernate routes via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DepositProduct {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "short_name", unique = true, nullable = false, length = 10) private String shortName;
    @Enumerated(EnumType.STRING) @Column(name = "account_type", nullable = false, length = 50) private AccountType accountType;
    @Column(name = "minimum_balance", nullable = false, precision = 19, scale = 4) private BigDecimal minimumBalance;
    @Column(name = "nominal_interest_rate", nullable = false, precision = 8, scale = 4) private BigDecimal nominalInterestRate;
    @Column(name = "allow_overdraft", nullable = false) private boolean allowOverdraft;
    @Column(name = "overdraft_limit", precision = 19, scale = 4) private BigDecimal overdraftLimit;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (accountType == null) accountType = AccountType.SAVINGS;
        if (minimumBalance == null) minimumBalance = BigDecimal.ZERO;
        if (nominalInterestRate == null) nominalInterestRate = BigDecimal.ZERO;
        active = true;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
