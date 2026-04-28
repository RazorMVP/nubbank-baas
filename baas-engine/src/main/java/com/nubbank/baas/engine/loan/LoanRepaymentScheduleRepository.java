package com.nubbank.baas.engine.loan;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LoanRepaymentScheduleRepository extends JpaRepository<LoanRepaymentSchedule, UUID> {
    Page<LoanRepaymentSchedule> findByLoanIdOrderByInstallmentNo(UUID loanId, Pageable pageable);
    List<LoanRepaymentSchedule> findByLoanIdAndStatusInOrderByInstallmentNo(UUID loanId, List<RepaymentStatus> statuses);
    List<LoanRepaymentSchedule> findByStatusAndDueDateBefore(RepaymentStatus status, LocalDate date);
}
