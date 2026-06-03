package com.nubbank.baas.card.authorize;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationIdempotencyRepository
        extends JpaRepository<AuthorizationIdempotency, UUID> {

    Optional<AuthorizationIdempotency> findByIdemKey(String idemKey);

    @Modifying
    @Transactional
    @Query("DELETE FROM AuthorizationIdempotency a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
