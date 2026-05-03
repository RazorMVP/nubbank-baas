package com.nubbank.baas.engine.cob;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class CobService {

    private final CobJobHistoryRepository historyRepo;
    private final PartnerOrganizationRepository orgRepo;
    private final CobJobExecutor executor;

    @Scheduled(cron = "0 55 23 * * *")
    public void runStandingOrders() {
        runForAllSchemas("standingOrderExecutionJob", executor::executeStandingOrders);
    }

    @Scheduled(cron = "0 57 23 * * *")
    public void runInterestAccrual() {
        runForAllSchemas("interestAccrualJob", executor::accrueInterest);
    }

    @Scheduled(cron = "0 59 23 * * *")
    public void runArrearsClassification() {
        runForAllSchemas("arrearsClassificationJob", executor::classifyArrears);
    }

    public void runJobManually(String jobName) {
        switch (jobName) {
            case "standingOrderExecutionJob" -> runForAllSchemas(jobName, executor::executeStandingOrders);
            case "interestAccrualJob" -> runForAllSchemas(jobName, executor::accrueInterest);
            case "arrearsClassificationJob" -> runForAllSchemas(jobName, executor::classifyArrears);
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
                    // Calls into the proxy bean — @Transactional fires correctly
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
}
