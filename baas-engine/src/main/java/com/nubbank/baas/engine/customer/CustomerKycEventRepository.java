package com.nubbank.baas.engine.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CustomerKycEventRepository extends JpaRepository<CustomerKycEvent, UUID> {
    List<CustomerKycEvent> findByCustomerIdOrderByChangedAtDesc(UUID customerId);
}
