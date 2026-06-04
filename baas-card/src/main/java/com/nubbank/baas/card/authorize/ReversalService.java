package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Locates the ORIGINAL authorization (by its idempotency key) and marks it reversed (F6).
 *
 * <p>Phase 1C does NOT move funds — the balance check is still a stub (DEF-1C-23), so a
 * reversal only flips {@code reversed=true} on the original idempotency row and reports
 * whether the original existed. Actual fund-reversal rides Phase 2 with the balance
 * wiring. This removes the prior defect (the FEP blanket-approved reversals for
 * transactions that never happened).
 *
 * <p>Same tenant-context discipline as {@link AuthorizationDecisionService}: set from the
 * request's {@code schemaName}, ALWAYS cleared in {@code finally}.
 */
@Service
@RequiredArgsConstructor
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final AuthorizationIdempotencyRepository idempotencyRepo;

    public ReversalResponse reverse(ReversalRequest req) {
        String environment =
            req.schemaName() != null && req.schemaName().startsWith("sandbox_")
                ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(
            req.partnerId(), req.schemaName(), "INTERNAL", environment, "INTERNAL", null));
        try {
            String key = req.originalStan() + "|" + req.terminalId()
                + "|" + req.originalTransmissionDateTime();
            Optional<AuthorizationIdempotency> original = idempotencyRepo.findByIdemKey(key);
            if (original.isEmpty()) {
                log.debug("Reversal: original not located rc=25");
                return new ReversalResponse(false);
            }
            AuthorizationIdempotency row = original.get();
            if (!row.isReversed()) {
                row.setReversed(true);
                idempotencyRepo.save(row);
            }
            log.debug("Reversal: original located + marked reversed rc=00");
            return new ReversalResponse(true);
        } finally {
            PartnerContext.clear();
        }
    }
}
