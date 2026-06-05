package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.engine.EngineClient;
import com.nubbank.baas.card.engine.dto.CardCreditRequest;
import com.nubbank.baas.card.engine.dto.CardCreditResult;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Locates the ORIGINAL authorization (by its idempotency key) and credits the cardholder
 * account back via the engine (Stage 5 — closes DEF-1C-25's fund half).
 *
 * <p>Credits only when the original decision was {@code APPROVE} and it has not already been
 * reversed; the engine credit is idempotent on the same {@code authKey}, so it never
 * double-credits even if the local flag races. A declined original has nothing to credit —
 * it is marked reversed and reported located. An unreachable engine on an APPROVE credit
 * returns {@code located=false} (FEP maps to RC 25) WITHOUT flipping {@code reversed}, so a
 * terminal retry (reversals are retried) completes the idempotent credit later.
 *
 * <p>Same tenant-context discipline as {@link AuthorizationDecisionService}: set from the
 * request's {@code schemaName}, ALWAYS cleared in {@code finally}.
 */
@Service
@RequiredArgsConstructor
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final AuthorizationIdempotencyRepository idempotencyRepo;
    private final EngineClient engineClient;

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
            if (row.isReversed()) {
                return new ReversalResponse(true);   // idempotent: already reversed
            }
            if (!"APPROVE".equals(row.getDecision())) {
                row.setReversed(true);               // declined original: nothing to credit
                idempotencyRepo.save(row);
                log.debug("Reversal: declined original located, no credit rc=00");
                return new ReversalResponse(true);
            }
            CardCreditResult credit = engineClient.cardCredit(
                new CardCreditRequest(req.partnerId(), req.schemaName(), key));
            if (!credit.located()) {
                // Engine unreachable or could not locate the debit — do NOT flip reversed;
                // the terminal retries and the engine credit is idempotent.
                log.debug("Reversal: engine credit not completed rc=25");
                return new ReversalResponse(false);
            }
            row.setReversed(true);
            idempotencyRepo.save(row);
            log.debug("Reversal: original credited + marked reversed rc=00");
            return new ReversalResponse(true);
        } finally {
            PartnerContext.clear();
        }
    }
}
