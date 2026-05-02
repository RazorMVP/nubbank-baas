package com.nubbank.baas.engine.compliance;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
