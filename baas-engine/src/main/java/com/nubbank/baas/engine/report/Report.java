package com.nubbank.baas.engine.report;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "reports")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Report {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false, length = 200) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "report_sql", columnDefinition = "TEXT", nullable = false) private String reportSql;
    @Column(length = 100) private String category;
    @Column(nullable = false) private boolean active;
    @Builder.Default
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.EAGER)
    private List<ReportParameter> parameters = new ArrayList<>();
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); active = true; }
}
