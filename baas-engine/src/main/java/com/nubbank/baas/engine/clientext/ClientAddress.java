package com.nubbank.baas.engine.clientext;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nubbank.baas.engine.common.FieldEncryptor;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "client_addresses")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientAddress {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "address_type", nullable = false, length = 50) private String addressType;
    /** Street address — regulated PII (GDPR/NDPR), encrypted at rest. */
    @Convert(converter = FieldEncryptor.class)
    @Column(length = 1000) private String street;
    /** City — regulated PII, encrypted at rest. */
    @Convert(converter = FieldEncryptor.class)
    @Column(length = 500) private String city;
    @Column(name = "state_province", length = 200) private String stateProvince;
    @Column(name = "country_code", length = 3) private String countryCode;
    /** Postal code — regulated PII, encrypted at rest. */
    @Convert(converter = FieldEncryptor.class)
    @Column(name = "postal_code", length = 200) private String postalCode;
    @JsonProperty("isActive")
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now(); active = true;
        if (addressType == null) addressType = "HOME";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
