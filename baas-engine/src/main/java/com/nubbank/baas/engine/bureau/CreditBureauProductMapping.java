package com.nubbank.baas.engine.bureau;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "credit_bureau_product_mappings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"loan_product_id","credit_bureau_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditBureauProductMapping {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "loan_product_id", nullable = false) private UUID loanProductId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "credit_bureau_id", nullable = false)
    @JsonIgnore private CreditBureauIntegration creditBureau;
    @Column(name = "credit_bureau_id", insertable = false, updatable = false) private UUID creditBureauId;
    @Column(name = "credit_check_mandatory", nullable = false) private boolean creditCheckMandatory;
}
