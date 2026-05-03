package com.nubbank.baas.engine.loan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_guarantors")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanGuarantor {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "loan_id", nullable = false) private Loan loan;
    @Column(name = "guarantor_type", nullable = false, length = 50) private String guarantorType;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id") private Customer customer;
    @Column(name = "first_name", length = 200) private String firstName;
    @Column(name = "last_name", length = 200) private String lastName;
    @Column(length = 255) private String email;
    @Column(length = 50) private String phone;
    @Column(name = "created_at", updatable = false) private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (guarantorType == null) guarantorType = "EXISTING_CUSTOMER";
    }
}
