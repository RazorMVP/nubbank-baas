package com.nubbank.baas.engine.twofa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface TwoFactorTokenRepository extends JpaRepository<TwoFactorToken, UUID> {

    /**
     * Atomic increment + lock decision in a single SQL UPDATE so concurrent
     * verification attempts can't race the read-modify-write. PostgreSQL
     * evaluates the SET clauses against the pre-update row, so
     * {@code failed_attempts + 1 >= :maxAttempts} computes the correct lock
     * condition for the post-update value.
     */
    @Modifying
    @Query(value = "UPDATE two_factor_tokens "
        + "SET failed_attempts = failed_attempts + 1, "
        + "    locked = (failed_attempts + 1 >= :maxAttempts) "
        + "WHERE id = :id", nativeQuery = true)
    int incrementFailedAttempts(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts);
}
