package com.nubbank.baas.engine.campaign;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "sms_campaigns")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SmsCampaign {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "campaign_type", nullable = false, length = 50) private String campaignType;
    @Column(name = "trigger_type", nullable = false, length = 50) private String triggerType;
    @Column(name = "message_template", columnDefinition = "TEXT", nullable = false)
    private String messageTemplate;
    @Column(length = 200) private String recurrence;
    @Column(nullable = false, length = 50) private String status;
    @Column(name = "activation_date") private LocalDate activationDate;
    @Version private Long version;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = "PENDING";
        if (campaignType == null) campaignType = "INDIVIDUAL";
        if (triggerType == null) triggerType = "DIRECT";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
