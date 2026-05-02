package com.nubbank.baas.engine.standing;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {
    Page<Beneficiary> findByCustomerIdAndActiveTrue(UUID customerId, Pageable pageable);
    Optional<Beneficiary> findByIdAndCustomerId(UUID id, UUID customerId);
}
