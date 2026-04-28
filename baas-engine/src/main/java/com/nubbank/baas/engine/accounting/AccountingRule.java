package com.nubbank.baas.engine.accounting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounting_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccountingRule {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "debit_account_id") private GlAccount debitAccount;
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "credit_account_id") private GlAccount creditAccount;
    @Column(name = "allow_multiple_debits", nullable = false) private boolean allowMultipleDebits;
    @Column(name = "allow_multiple_credits", nullable = false) private boolean allowMultipleCredits;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
