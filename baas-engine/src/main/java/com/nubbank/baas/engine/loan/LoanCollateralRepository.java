package com.nubbank.baas.engine.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LoanCollateralRepository extends JpaRepository<LoanCollateral, UUID> {
    List<LoanCollateral> findByLoanId(UUID loanId);
}
