package com.nubbank.baas.engine.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PartnerUserRepository extends JpaRepository<PartnerUser, UUID> {
    Optional<PartnerUser> findByEmailAndActiveTrue(String email);
    boolean existsByEmail(String email);
    java.util.List<PartnerUser> findByOrganization_Id(java.util.UUID orgId);
    long countByOrganization_IdAndActiveTrue(java.util.UUID orgId);
}
