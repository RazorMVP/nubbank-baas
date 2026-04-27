package com.nubbank.baas.engine.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerApiKeyRepository extends JpaRepository<PartnerApiKey, UUID> {
    Optional<PartnerApiKey> findByKeyHashAndActiveTrue(String keyHash);
    List<PartnerApiKey> findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(UUID orgId);

    @Modifying
    @Query("UPDATE PartnerApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    void updateLastUsed(UUID id, Instant now);
}
