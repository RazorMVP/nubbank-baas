package com.nubbank.baas.engine.accounting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "provisioning_criteria_definitions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProvisioningCriteriaDefinition {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "criteria_id", nullable = false)
    private ProvisioningCriteria criteria;
    @Column(name = "category_name", nullable = false, length = 100) private String categoryName;
    @Column(name = "min_age", nullable = false) private Integer minAge;
    @Column(name = "max_age", nullable = false) private Integer maxAge;
    @Column(name = "provision_percentage", nullable = false, precision = 5, scale = 2) private BigDecimal provisionPercentage;
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "liability_account_id") private GlAccount liabilityAccount;
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "expense_account_id") private GlAccount expenseAccount;
}
