package com.nubbank.baas.engine.customer;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByExternalReference(String externalReference);
    Optional<Customer> findByExternalReference(String externalReference);
    Page<Customer> findByKycStatus(KycStatus status, Pageable pageable);

    /** Dashboard tile (DEF-1C-29) — count of customers in a given KYC state. */
    long countByKycStatus(KycStatus status);

    /**
     * Filtered list supporting:
     * - kycStatus filter (nullable — omit to match all statuses)
     * - blind-index name prefix search via Postgres {@code @>} array containment
     * - external_reference ILIKE fallback for non-name search terms (e.g. "ext-200")
     *
     * Parameters:
     *   status    — kyc_status string value, or NULL to skip status filter
     *   hasSearch — true when a search term was supplied; false skips the token/extRef checks
     *   tokens    — Postgres text[] literal, e.g. "{hash1,hash2}" — the HMAC prefix hashes
     *   extRef    — ILIKE pattern e.g. "%ext-200%" for external_reference fallback, or NULL
     */
    @Query(value = """
        SELECT * FROM customers c
        WHERE (CAST(:status AS text) IS NULL OR c.kyc_status = CAST(:status AS text))
          AND (
                :hasSearch = FALSE
                OR c.name_search_tokens @> CAST(:tokens AS text[])
                OR (CAST(:extRef AS text) IS NOT NULL
                    AND c.external_reference ILIKE CAST(:extRef AS text))
              )
        ORDER BY c.created_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM customers c
        WHERE (CAST(:status AS text) IS NULL OR c.kyc_status = CAST(:status AS text))
          AND (
                :hasSearch = FALSE
                OR c.name_search_tokens @> CAST(:tokens AS text[])
                OR (CAST(:extRef AS text) IS NOT NULL
                    AND c.external_reference ILIKE CAST(:extRef AS text))
              )
        """,
        nativeQuery = true)
    Page<Customer> search(
        @Param("status")    String status,
        @Param("hasSearch") boolean hasSearch,
        @Param("tokens")    String tokens,
        @Param("extRef")    String extRef,
        Pageable pageable);
}
