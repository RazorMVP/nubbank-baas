package com.nubbank.baas.engine.accounting;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gl_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GlAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "gl_code", unique = true, nullable = false, length = 50) private String glCode;
    @Enumerated(EnumType.STRING) @Column(name = "account_type", nullable = false, length = 50) private GlAccountType accountType;
    @Column(name = "account_usage", nullable = false, length = 50) private String accountUsage;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") private GlAccount parent;
    @Column(name = "manual_journal_entries_allowed", nullable = false) private boolean manualJournalEntriesAllowed;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private boolean disabled;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (accountUsage == null) accountUsage = "DETAIL";
        manualJournalEntriesAllowed = true;
        disabled = false;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
