package com.nubbank.baas.engine.standing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiaries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Beneficiary {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "account_number", nullable = false, length = 20) private String accountNumber;
    @Column(name = "account_name", length = 200) private String accountName;
    @Column(name = "bank_code", length = 10) private String bankCode;
    @Column(name = "bank_name", length = 200) private String bankName;
    @Column(name = "transfer_limit", precision = 19, scale = 4) private BigDecimal transferLimit;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); active = true; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
