package com.nubbank.baas.engine.charge;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "charges")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Charge {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Enumerated(EnumType.STRING) @Column(name = "charge_type", nullable = false, length = 50) private ChargeType chargeType;
    @Enumerated(EnumType.STRING) @Column(name = "calculation_type", nullable = false, length = 50) private CalculationType calculationType;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (calculationType == null) calculationType = CalculationType.FLAT;
        if (currencyCode == null) currencyCode = "NGN";
        active = true;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
