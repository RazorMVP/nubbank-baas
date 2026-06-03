package com.nubbank.baas.card.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Card}. Every query is routed to the
 * authenticated partner's schema by Hibernate's tenant resolver — there is no
 * partnerId filter because the schema IS the boundary.
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    /**
     * FROZEN CROSS-TRACK CONTRACT: Task 6's authorize resolves a card from a PAN via
     * {@code findByPanHash(panHasher.hash(pan))}. The {@code pan_hash} UNIQUE
     * constraint guarantees at most one match within the tenant.
     */
    Optional<Card> findByPanHash(String panHash);
}
