package com.nubbank.baas.engine.openbanking;

import com.nubbank.baas.engine.customer.Customer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "open_banking_consents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OpenBankingConsent {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    @JsonIgnore private Customer customer;

    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;

    @Column(name = "tpp_client_id", nullable = false, length = 255)
    private String tppClientId;

    @Column(name = "tpp_name", length = 200)
    private String tppName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ConsentStatus status;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> scopes = new ArrayList<>();

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "access_frequency", length = 50)
    private String accessFrequency;

    @Column(name = "authorised_at")
    private Instant authorisedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version private Long version;

    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (status == null) status = ConsentStatus.AWAITING_AUTHORISATION;
        if (scopes == null) scopes = new ArrayList<>();
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
