package com.nubbank.baas.card.partner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PartnerApiKeyRepository extends JpaRepository<PartnerApiKey, UUID> {
    Optional<PartnerApiKey> findByKeyHash(String keyHash);
    Optional<PartnerApiKey> findByKeyHashAndActiveTrue(String keyHash);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE PartnerApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    void updateLastUsed(UUID id, Instant now);
}
