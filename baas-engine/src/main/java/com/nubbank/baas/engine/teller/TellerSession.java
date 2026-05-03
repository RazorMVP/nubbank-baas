package com.nubbank.baas.engine.teller;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "teller_sessions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"cashier_id", "session_date"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TellerSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "teller_id", nullable = false) private Teller teller;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "cashier_id", nullable = false) private Cashier cashier;
    @Column(name = "session_date", nullable = false) private LocalDate sessionDate;
    @Column(nullable = false, length = 50) private String status;
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 4) private BigDecimal openingBalance;
    @Column(name = "closing_balance", precision = 19, scale = 4) private BigDecimal closingBalance;
    @Column(name = "actual_cash", precision = 19, scale = 4) private BigDecimal actualCash;
    @Column(precision = 19, scale = 4) private BigDecimal difference;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Version private Long version;
    @Column(name = "opened_at", updatable = false) private Instant openedAt;
    @Column(name = "closed_at") private Instant closedAt;

    @PrePersist void onCreate() {
        openedAt = Instant.now();
        if (status == null) status = "OPEN";
        if (sessionDate == null) sessionDate = LocalDate.now();
        if (currencyCode == null) currencyCode = "NGN";
    }
}
