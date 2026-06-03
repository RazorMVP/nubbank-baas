package com.nubbank.baas.card.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PartnerOrganizationRepository extends JpaRepository<PartnerOrganization, UUID> {
    Optional<PartnerOrganization> findBySchemaName(String schemaName);
}
