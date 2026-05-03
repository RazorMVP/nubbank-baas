package com.nubbank.baas.engine.accounting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "financial_activity_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FinancialActivityAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "activity_name", unique = true, nullable = false, length = 100) private String activityName;
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "gl_account_id", nullable = false) private GlAccount glAccount;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
