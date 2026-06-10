package com.nubbank.baas.engine.loan;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {
    Page<Loan> findByCustomerId(UUID customerId, Pageable pageable);
    Page<Loan> findByStatus(LoanStatus status, Pageable pageable);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Loan l WHERE l.id = :id")
    Optional<Loan> findByIdForUpdate(UUID id);

    /** Dashboard "active loans" tile (DEF-1C-29) — disbursed + active + in-arrears. */
    @Query("SELECT COUNT(l) FROM Loan l WHERE l.status IN :statuses")
    long countByStatusIn(java.util.List<LoanStatus> statuses);
}
