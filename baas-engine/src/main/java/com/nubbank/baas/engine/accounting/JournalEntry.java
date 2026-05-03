package com.nubbank.baas.engine.accounting;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "journal_entries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JournalEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "entry_date", nullable = false) private LocalDate entryDate;
    @Column(length = 100) private String reference;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "entity_type", length = 100) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(nullable = false) private boolean manual;
    @Column(nullable = false) private boolean reversed;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reversed_by_id") private JournalEntry reversedBy;
    @Column(name = "created_by", length = 255) private String createdBy;
    @Version private Long version;
    @JsonManagedReference
    @Builder.Default
    @OneToMany(mappedBy = "journal", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalEntryLine> lines = new ArrayList<>();
    @Column(name = "created_at", updatable = false) private Instant createdAt;

    @PrePersist void onCreate() {
        createdAt = Instant.now();
        if (!manual) manual = false;
        if (!reversed) reversed = false;
    }
}
