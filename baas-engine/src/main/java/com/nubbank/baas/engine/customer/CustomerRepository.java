package com.nubbank.baas.engine.customer;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByExternalReference(String externalReference);
    Optional<Customer> findByExternalReference(String externalReference);
    Page<Customer> findByKycStatus(KycStatus status, Pageable pageable);
}
