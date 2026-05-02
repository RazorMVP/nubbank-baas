package com.nubbank.baas.engine.standing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.account.Account;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "standing_instructions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StandingInstruction {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "source_account_id", nullable = false)
    @JsonIgnore private Account sourceAccount;
    @Column(name = "source_account_id", insertable = false, updatable = false) private UUID sourceAccountId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "destination_account_id", nullable = false)
    @JsonIgnore private Account destinationAccount;
    @Column(name = "destination_account_id", insertable = false, updatable = false) private UUID destinationAccountId;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "instruction_type", nullable = false, length = 50) private String instructionType;
    @Column(nullable = false, length = 50) private String priority;
    @Column(nullable = false, length = 50) private String status;
    @Column(precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "recurrence_frequency", nullable = false, length = 20) private String recurrenceFrequency;
    @Column(name = "recurrence_interval", nullable = false) private int recurrenceInterval;
    @Column(name = "valid_from") private LocalDate validFrom;
    @Column(name = "valid_to") private LocalDate validTo;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = "ACTIVE";
        if (priority == null) priority = "MEDIUM";
        if (instructionType == null) instructionType = "FIXED";
        if (recurrenceFrequency == null) recurrenceFrequency = "MONTHS";
        if (recurrenceInterval == 0) recurrenceInterval = 1;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
