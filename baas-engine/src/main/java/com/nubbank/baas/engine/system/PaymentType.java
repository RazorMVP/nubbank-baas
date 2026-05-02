package com.nubbank.baas.engine.system;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentType {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @JsonProperty("isCashPayment")
    @Column(name = "is_cash_payment", nullable = false) private boolean cashPayment;
    @Column(name = "system_defined", nullable = false) private boolean systemDefined;
    @Column(nullable = false) private int position;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
