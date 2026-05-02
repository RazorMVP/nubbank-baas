package com.nubbank.baas.engine.cob;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.loan.*;
import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.standing.StandingInstruction;
import com.nubbank.baas.engine.standing.StandingInstructionRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class CobService {

    private final CobJobHistoryRepository historyRepo;
    private final PartnerOrganizationRepository orgRepo;
    private final LoanRepository loanRepo;
    private final LoanRepaymentScheduleRepository scheduleRepo;
    private final StandingInstructionRepository siRepo;

    @Scheduled(cron = "0 55 23 * * *")
    public void runStandingOrders() {
        runForAllSchemas("standingOrderExecutionJob", this::executeStandingOrders);
    }

    @Scheduled(cron = "0 57 23 * * *")
    public void runInterestAccrual() {
        runForAllSchemas("interestAccrualJob", this::accrueInterest);
    }

    @Scheduled(cron = "0 59 23 * * *")
    public void runArrearsClassification() {
        runForAllSchemas("arrearsClassificationJob", this::classifyArrears);
    }

    public void runJobManually(String jobName) {
        switch (jobName) {
            case "standingOrderExecutionJob" -> runForAllSchemas(jobName, this::executeStandingOrders);
            case "interestAccrualJob" -> runForAllSchemas(jobName, this::accrueInterest);
            case "arrearsClassificationJob" -> runForAllSchemas(jobName, this::classifyArrears);
            default -> throw BaasException.badRequest("UNKNOWN_JOB", "Unknown job: " + jobName);
        }
    }

    private void runForAllSchemas(String jobName, Consumer<String> task) {
        // History entry persisted in the public schema (no PartnerContext needed)
        CobJobHistory history = historyRepo.save(CobJobHistory.builder().jobName(jobName).build());
        int processed = 0;
        try {
            List<String> schemas = orgRepo.findAll().stream()
                .map(org -> org.getSchemaName()).toList();
            for (String schema : schemas) {
                try {
                    PartnerContext.set(new PartnerContext("system", schema,
                        "BASIC", "PRODUCTION", "COB", null));
                    task.accept(schema);
                    processed++;
                } catch (Exception e) {
                    log.error("CoB job {} failed for schema {}: {}", jobName, schema, e.getMessage());
                } finally {
                    PartnerContext.clear();
                }
            }
            history.setStatus("COMPLETED");
            history.setRecordsProcessed(processed);
        } catch (Exception e) {
            history.setStatus("FAILED");
            history.setErrorMessage(e.getMessage());
        } finally {
            history.setCompletedAt(Instant.now());
            historyRepo.save(history);
        }
    }

    @Transactional
    private void accrueInterest(String schema) {
        // Phase 1 stub — interest accrual configured per product in Phase 2
        log.debug("Interest accrual stub for schema {}", schema);
    }

    @Transactional
    private void classifyArrears(String schema) {
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
    private void executeStandingOrders(String schema) {
        List<StandingInstruction> active = siRepo.findByStatus("ACTIVE");
        log.debug("Standing orders: {} active instructions in schema {}", active.size(), schema);
    }
}
