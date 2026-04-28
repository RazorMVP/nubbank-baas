package com.nubbank.baas.engine.loan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_collaterals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanCollateral {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "loan_id", nullable = false) private Loan loan;
    @Column(columnDefinition = "TEXT", nullable = false) private String description;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal value;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (currencyCode == null) currencyCode = "NGN";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
