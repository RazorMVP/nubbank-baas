package com.nubbank.baas.engine.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
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
}
