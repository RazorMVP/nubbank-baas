package com.nubbank.baas.engine.accounting;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "journal_entry_lines")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JournalEntryLine {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "journal_id", nullable = false) private JournalEntry journal;
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "gl_account_id", nullable = false) private GlAccount glAccount;
    @Column(name = "entry_type", nullable = false, length = 10) private String entryType;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;

    @PrePersist void onCreate() { if (currencyCode == null) currencyCode = "NGN"; }
}
