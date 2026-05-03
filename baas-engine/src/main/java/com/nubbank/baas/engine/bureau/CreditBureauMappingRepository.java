package com.nubbank.baas.engine.bureau;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CreditBureauMappingRepository extends JpaRepository<CreditBureauProductMapping, UUID> {
    List<CreditBureauProductMapping> findByCreditBureauId(UUID bureauId);
}
