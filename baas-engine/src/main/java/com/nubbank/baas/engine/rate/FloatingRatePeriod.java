package com.nubbank.baas.engine.rate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "floating_rate_periods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FloatingRatePeriod {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "floating_rate_id", nullable = false)
    @JsonIgnore private FloatingRate floatingRate;
    @Column(name = "from_date", nullable = false) private LocalDate fromDate;
    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;
    @JsonProperty("isDifferentialToBaseLending")
    @Column(name = "is_differential_to_base_lending", nullable = false)
    private boolean differentialToBaseLending;
}
