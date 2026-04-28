package com.nubbank.baas.engine.teller;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cash_transactions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CashTransaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "session_id", nullable = false) private TellerSession session;
    @Column(name = "transaction_type", nullable = false, length = 50) private String transactionType;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "account_id") private UUID accountId;
    @Column(length = 500) private String description;
    @Column(name = "created_at", updatable = false) private Instant createdAt;

    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
