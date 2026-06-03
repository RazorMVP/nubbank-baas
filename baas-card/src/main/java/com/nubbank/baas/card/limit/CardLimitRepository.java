package com.nubbank.baas.card.limit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link CardLimit}. Every query is routed to the
 * authenticated partner's schema by Hibernate's tenant resolver — there is no
 * partnerId filter because the schema IS the boundary.
 */
public interface CardLimitRepository extends JpaRepository<CardLimit, UUID> {

    /** Upsert lookup: at most one row per card (enforced by the card_id UNIQUE constraint). */
    Optional<CardLimit> findByCardId(UUID cardId);
}
