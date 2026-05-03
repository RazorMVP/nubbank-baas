package com.nubbank.baas.engine.product;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_products")
// NO schema = "public" — this is a TENANT table; Hibernate routes via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanProduct {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "short_name", unique = true, nullable = false, length = 10) private String shortName;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "min_principal", nullable = false, precision = 19, scale = 4) private BigDecimal minPrincipal;
    @Column(name = "max_principal", nullable = false, precision = 19, scale = 4) private BigDecimal maxPrincipal;
    @Column(name = "default_principal", nullable = false, precision = 19, scale = 4) private BigDecimal defaultPrincipal;
    @Column(name = "nominal_interest_rate", nullable = false, precision = 8, scale = 4) private BigDecimal nominalInterestRate;
    @Enumerated(EnumType.STRING) @Column(name = "repayment_type", nullable = false, length = 50) private RepaymentType repaymentType;
    @Column(name = "number_of_repayments", nullable = false) private Integer numberOfRepayments;
    @Column(name = "repayment_every", nullable = false) private Integer repaymentEvery;
    @Column(name = "repayment_frequency", nullable = false, length = 20) private String repaymentFrequency;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (repaymentType == null) repaymentType = RepaymentType.ANNUITY;
        if (repaymentEvery == null) repaymentEvery = 1;
        if (repaymentFrequency == null) repaymentFrequency = "MONTHS";
        active = true;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
