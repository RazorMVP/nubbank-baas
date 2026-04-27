package com.nubbank.baas.engine.customer;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "customers")
// NO schema = "public" — this is a TENANT table; Hibernate routes via PartnerSchemaProvider
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_reference", unique = true, length = 100)
    private String externalReference;

    @Column(name = "first_name_encrypted", nullable = false, length = 500)
    private String firstNameEncrypted;

    @Column(name = "last_name_encrypted", nullable = false, length = 500)
    private String lastNameEncrypted;

    @Column(name = "email_encrypted", length = 500)
    private String emailEncrypted;

    @Column(name = "phone_encrypted", length = 500)
    private String phoneEncrypted;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 20)
    private String gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 50)
    private KycStatus kycStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_level", nullable = false, length = 50)
    private KycLevel kycLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_provider", length = 50)
    private KycProvider kycProvider;

    @Column(name = "bvn_encrypted", length = 500)
    private String bvnEncrypted;

    @Column(name = "nin_encrypted", length = 500)
    private String ninEncrypted;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (kycStatus == null) kycStatus = KycStatus.PENDING_KYC;
        if (kycLevel == null) kycLevel = KycLevel.NONE;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
