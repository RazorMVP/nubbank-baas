package com.nubbank.baas.engine.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.*;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByAccountNumber(String accountNumber);
    Page<Account> findByCustomerId(UUID customerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);

    /** Dashboard tiles (DEF-1C-29). */
    long countByStatus(AccountStatus status);

    @Query("SELECT COALESCE(SUM(a.balance), 0) FROM Account a WHERE a.status = :status")
    BigDecimal sumBalanceByStatus(AccountStatus status);

    /**
     * Paginated account list with customer eagerly fetched (so customerName resolves with no
     * second round-trip). JOIN FETCH requires an explicit countQuery — Spring Data cannot
     * derive a count over a fetch join.
     *   status — exact AccountStatus filter, or null to match all statuses
     *   search — case-insensitive prefix pattern on account_number (e.g. "012345%"),
     *            already lower-cased by the service; or null to skip
     */
    @Query(value = """
        SELECT a FROM Account a JOIN FETCH a.customer
        WHERE (:status IS NULL OR a.status = :status)
          AND (:search IS NULL OR LOWER(a.accountNumber) LIKE :search)
        ORDER BY a.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(a) FROM Account a
        WHERE (:status IS NULL OR a.status = :status)
          AND (:search IS NULL OR LOWER(a.accountNumber) LIKE :search)
        """)
    Page<Account> search(@Param("status") AccountStatus status,
                         @Param("search") String search,
                         Pageable pageable);
}
