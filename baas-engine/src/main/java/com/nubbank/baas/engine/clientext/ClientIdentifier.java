package com.nubbank.baas.engine.clientext;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.common.FieldEncryptor;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "client_identifiers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientIdentifier {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "document_type", nullable = false, length = 100) private String documentType;
    /** Passport / driver's licence / SSN / NIN — encrypted at rest. */
    @Convert(converter = FieldEncryptor.class)
    @Column(name = "document_key", nullable = false, length = 500) private String documentKey;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "expiry_date") private LocalDate expiryDate;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); active = true; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
