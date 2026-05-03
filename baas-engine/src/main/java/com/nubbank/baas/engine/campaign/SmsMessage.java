package com.nubbank.baas.engine.campaign;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sms_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SmsMessage {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "campaign_id", nullable = false)
    @JsonIgnore private SmsCampaign campaign;
    @Column(name = "campaign_id", insertable = false, updatable = false) private UUID campaignId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id")
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "phone_number", length = 50) private String phoneNumber;
    @Column(columnDefinition = "TEXT", nullable = false) private String message;
    @Column(name = "delivery_status", nullable = false, length = 50) private String deliveryStatus;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "sent_at") private Instant sentAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now();
        if (deliveryStatus == null) deliveryStatus = "PENDING";
    }
}
