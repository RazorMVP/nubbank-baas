package com.nubbank.baas.engine.virtualaccount;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "virtual_account_pool", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VirtualAccountPool {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "bank_code", nullable = false, length = 3)
    private String bankCode;

    @Column(nullable = false)
    private boolean assigned;

    @Column(name = "assigned_to_schema", length = 100)
    private String assignedToSchema;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
