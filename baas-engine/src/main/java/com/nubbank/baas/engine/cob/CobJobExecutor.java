package com.nubbank.baas.engine.cob;

import com.nubbank.baas.engine.loan.*;
import com.nubbank.baas.engine.standing.StandingInstruction;
import com.nubbank.baas.engine.standing.StandingInstructionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

/**
 * CoB job executor — separate Spring bean so @Transactional methods are
 * intercepted by the Spring AOP proxy. {@link CobService} called these as
 * private methods on itself, which silently disabled the @Transactional
 * because internal calls bypass the proxy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CobJobExecutor {

    private final LoanRepository loanRepo;
    private final LoanRepaymentScheduleRepository scheduleRepo;
    private final StandingInstructionRepository siRepo;

    @Transactional
    public void accrueInterest(String schema) {
        // Phase 1 stub — interest accrual configured per product in Phase 2
        log.debug("Interest accrual stub for schema {}", schema);
    }

    @Transactional
    public void classifyArrears(String schema) {
        LocalDate today = LocalDate.now();
        List<LoanRepaymentSchedule> overdueSchedules =
            scheduleRepo.findByStatusAndDueDateBefore(RepaymentStatus.PENDING, today);
        for (LoanRepaymentSchedule s : overdueSchedules) {
            s.setStatus(RepaymentStatus.OVERDUE);
            scheduleRepo.save(s);
            Loan loan = s.getLoan();
            if (loan != null && loan.getStatus() == LoanStatus.ACTIVE) {
                loan.setStatus(LoanStatus.IN_ARREARS);
                loanRepo.save(loan);
            }
        }
        log.debug("Arrears classification: {} overdue installments in schema {}",
            overdueSchedules.size(), schema);
    }

    @Transactional
    public void executeStandingOrders(String schema) {
        List<StandingInstruction> active = siRepo.findByStatus("ACTIVE");
        log.debug("Standing orders: {} active instructions in schema {}", active.size(), schema);
    }
}
