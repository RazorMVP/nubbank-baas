package com.nubbank.baas.engine.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerApiKeyRepository extends JpaRepository<PartnerApiKey, UUID> {
    /**
     * Eagerly fetches organization to avoid LazyInitializationException in PartnerContextFilter,
     * which accesses key.getOrganization().getId() and key.getOrganization().getSchemaName()
     * outside of a transaction.
     */
    @Query("SELECT k FROM PartnerApiKey k JOIN FETCH k.organization WHERE k.keyHash = :keyHash AND k.active = true")
    Optional<PartnerApiKey> findByKeyHashAndActiveTrue(String keyHash);
    List<PartnerApiKey> findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(UUID orgId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE PartnerApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    void updateLastUsed(UUID id, Instant now);
}
