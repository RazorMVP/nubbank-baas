package com.nubbank.baas.engine.compliance;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceService {

    private final SanctionsScreeningRepository screenRepo;
    private final CustomerRepository customerRepo;
    private final Environment env;

    @Value("${app.compliance.allow-stub:false}")
    private boolean allowStub;

    /**
     * Refuse to start in production unless an explicit override flag is set.
     * The Phase 1 implementation is a stub that always returns CLEAR — deploying
     * it to a real customer-facing environment without a real provider would be
     * a regulatory failure. Phase 2 wires the live Ncube/NIBSS sanctions API.
     */
    @PostConstruct
    void assertNotProductionWithoutOverride() {
        boolean isProduction = false;
        for (String profile : env.getActiveProfiles()) {
            if ("production".equalsIgnoreCase(profile) || "prod".equalsIgnoreCase(profile)) {
                isProduction = true;
                break;
            }
        }
        if (isProduction && !allowStub) {
            throw new IllegalStateException(
                "ComplianceService stub started under a production profile without "
                + "app.compliance.allow-stub=true. The stub always returns CLEAR — "
                + "configure a real sanctions provider before deploying to production, "
                + "or set the override flag explicitly if running a dry-run.");
        }
        if (allowStub) {
            log.warn("ComplianceService is running in STUB mode (always returns CLEAR). "
                + "This must be replaced with a real provider before customer-facing launch.");
        }
    }

    @Transactional
    public SanctionsScreeningResult screenCustomer(UUID customerId) {
        customerRepo.findById(customerId)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));

        screenRepo.save(SanctionsScreeningLog.builder()
            .entityType("CUSTOMER").entityId(customerId)
            .screenType("NAME_MATCH").result("CLEAR")
            .notes("Phase 1 stub — live Ncube screening deferred to Phase 2")
            .provider("INTERNAL_STUB").build());

        return new SanctionsScreeningResult(customerId, "CUSTOMER",
            "NAME_MATCH", "CLEAR", "INTERNAL_STUB", "Phase 1 stub");
    }

    @Transactional
    public SanctionsScreeningResult screenPayment(UUID paymentId) {
        screenRepo.save(SanctionsScreeningLog.builder()
            .entityType("PAYMENT").entityId(paymentId)
            .screenType("PAYMENT_PATTERN").result("CLEAR")
            .notes("Phase 1 stub").provider("INTERNAL_STUB").build());
        return new SanctionsScreeningResult(paymentId, "PAYMENT",
            "PAYMENT_PATTERN", "CLEAR", "INTERNAL_STUB", "Phase 1 stub");
    }

    @Transactional(readOnly = true)
    public Page<SanctionsScreeningLog> listScreenings(String entityType, UUID entityId,
                                                      int page, int size) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        return screenRepo.findByEntityTypeAndEntityIdOrderByScreenedAtDesc(
            entityType.toUpperCase(), entityId, PageRequest.of(page, size));
    }
}
