package com.nubbank.baas.engine.compliance;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SanctionsScreeningRepository extends JpaRepository<SanctionsScreeningLog, UUID> {
    Page<SanctionsScreeningLog> findByEntityTypeAndEntityIdOrderByScreenedAtDesc(
        String entityType, UUID entityId, Pageable pageable);
}
