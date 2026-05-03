package com.nubbank.baas.engine.campaign;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_mailing_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportMailingJob {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "report_name", nullable = false, length = 200) private String reportName;
    @Column(name = "email_recipients", columnDefinition = "TEXT", nullable = false)
    private String emailRecipients;
    @Column(name = "output_type", nullable = false, length = 20) private String outputType;
    @Column(length = 200) private String recurrence;
    @Column(nullable = false) private int runCount;
    @Column(name = "previous_run_status", length = 50) private String previousRunStatus;
    @Column(name = "previous_run_start") private Instant previousRunStart;
    @Column(name = "previous_run_end") private Instant previousRunEnd;
    @Column(nullable = false) private boolean active;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        active = true; runCount = 0;
        if (outputType == null) outputType = "CSV";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
