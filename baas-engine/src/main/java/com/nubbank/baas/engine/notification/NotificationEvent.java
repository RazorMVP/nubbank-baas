package com.nubbank.baas.engine.notification;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Column(name = "entity_type", length = 100) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50) private NotificationChannel channel;
    @Column(length = 255) private String recipient;
    @Column(length = 500) private String subject;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private String payload;
    @Column(nullable = false, length = 50) private String status;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now();
        if (status == null) status = "PENDING";
        if (channel == null) channel = NotificationChannel.EMAIL;
    }
}
