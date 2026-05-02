package com.nubbank.baas.engine.clientext;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ClientIdentifierRepository extends JpaRepository<ClientIdentifier, UUID> {
    List<ClientIdentifier> findByCustomerIdAndActiveTrue(UUID customerId);
}
