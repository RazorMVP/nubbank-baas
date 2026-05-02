package com.nubbank.baas.engine.clientext;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ClientImageRepository extends JpaRepository<ClientImage, UUID> {
    Optional<ClientImage> findByCustomerId(UUID customerId);
}
