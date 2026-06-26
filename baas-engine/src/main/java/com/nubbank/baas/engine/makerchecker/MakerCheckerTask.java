package com.nubbank.baas.engine.makerchecker;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maker_checker_tasks")  // tenant-schema table — NO schema annotation
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MakerCheckerTask {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "command_type", nullable = false, length = 100)
    private String commandType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "made_by", nullable = false)
    private UUID madeBy;

    @Column(name = "made_at", nullable = false)
    private Instant madeAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "checked_by")
    private UUID checkedBy;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    @Column(name = "result_id")
    private UUID resultId;

    @Column(name = "expires_at")
    private Instant expiresAt;       // reserved TTL seam — unused in v1

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (madeAt == null) madeAt = now;
        if (status == null) status = TaskStatus.PENDING;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
