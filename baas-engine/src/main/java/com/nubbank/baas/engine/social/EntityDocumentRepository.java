package com.nubbank.baas.engine.social;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface EntityDocumentRepository extends JpaRepository<EntityDocument, UUID> {
    Page<EntityDocument> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
        String entityType, UUID entityId, Pageable pageable);
}
