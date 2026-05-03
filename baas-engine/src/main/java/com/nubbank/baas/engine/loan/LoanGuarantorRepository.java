package com.nubbank.baas.engine.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LoanGuarantorRepository extends JpaRepository<LoanGuarantor, UUID> {
    List<LoanGuarantor> findByLoanId(UUID loanId);
}
