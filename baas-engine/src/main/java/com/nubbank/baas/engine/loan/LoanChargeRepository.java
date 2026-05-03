package com.nubbank.baas.engine.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LoanChargeRepository extends JpaRepository<LoanCharge, UUID> {
    List<LoanCharge> findByLoanId(UUID loanId);
}
