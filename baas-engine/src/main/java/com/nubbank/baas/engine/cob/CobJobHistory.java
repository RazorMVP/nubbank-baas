package com.nubbank.baas.engine.cob;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cob_job_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CobJobHistory {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "job_name", nullable = false, length = 100) private String jobName;
    @Column(nullable = false, length = 50) private String status;
    @Column(name = "started_at", updatable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "records_processed", nullable = false) private int recordsProcessed;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @PrePersist void onCreate() {
        startedAt = Instant.now();
        if (status == null) status = "RUNNING";
    }
}
