package com.nubbank.baas.engine.rate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.accounting.GlAccount;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "tax_components")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxComponent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, precision = 19, scale = 6) private BigDecimal percentage;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "credit_account_id")
    @JsonIgnore private GlAccount creditAccount;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "debit_account_id")
    @JsonIgnore private GlAccount debitAccount;
    @Column(name = "credit_account_id", insertable = false, updatable = false) private UUID creditAccountId;
    @Column(name = "debit_account_id", insertable = false, updatable = false) private UUID debitAccountId;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
