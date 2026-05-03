package com.nubbank.baas.engine.clientext;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ClientAddressRepository extends JpaRepository<ClientAddress, UUID> {
    List<ClientAddress> findByCustomerIdAndActiveTrue(UUID customerId);
}
