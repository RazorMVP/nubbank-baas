package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionResponse;
import com.nubbank.baas.card.card.Card;
import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.card.CardStatus;
import com.nubbank.baas.card.card.PanHasher;
import com.nubbank.baas.card.common.CurrencyMinorUnits;
import com.nubbank.baas.card.limit.CardLimit;
import com.nubbank.baas.card.limit.CardLimitRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Internal card-authorization-decision — FROZEN CROSS-TRACK CONTRACT §2a.
 *
 * <p>The FEP (tenant-less, over HMAC) resolved the tenant via BIN lookup and passes
 * {@code schemaName + PAN + amount + currency + ISO trace (stan/terminal/dts)}. This
 * service is the ONE place baas-card sets {@link PartnerContext} itself; the set is
 * paired with an UNCONDITIONAL {@code finally { PartnerContext.clear(); }} — a leaked
 * ThreadLocal would route the next pooled-thread request to the wrong tenant.
 *
 * <p>Hardening (Session 11):
 * <ul>
 *   <li>F5 — environment is derived from the schema prefix ({@code sandbox_…}→SANDBOX),
 *       not hardcoded.</li>
 *   <li>F3 — idempotent on {@code stan|terminalId|transmissionDateTime}: a retransmit
 *       returns the cached decision instead of re-deciding.</li>
 *   <li>F1 — the amount is scaled by the currency's real minor-unit exponent
 *       (JDK-derived); an unknown currency declines RC {@code 12}.</li>
 *   <li>F2 — the per-txn limit is enforced only when the limit currency equals the
 *       transaction currency; a mismatch (or null limit currency) declines RC {@code 57}.</li>
 * </ul>
 * Balance remains a STUB (always sufficient) — real balance via baas-engine in Phase 2.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionService.class);

    private final CardRepository cardRepo;
    private final CardLimitRepository limitRepo;
    private final PanHasher panHasher;
    private final CurrencyMinorUnits currencyMinorUnits;
    private final AuthorizationIdempotencyRepository idempotencyRepo;

    public AuthorizationDecisionResponse decide(AuthorizationDecisionRequest req) {
        String environment =
            req.schemaName() != null && req.schemaName().startsWith("sandbox_")
                ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(
            req.partnerId(), req.schemaName(), "INTERNAL", environment, "INTERNAL", null));
        try {
            String idemKey = idemKey(req);

            if (idemKey != null) {
                Optional<AuthorizationIdempotency> cached = idempotencyRepo.findByIdemKey(idemKey);
                if (cached.isPresent()) {
                    AuthorizationIdempotency e = cached.get();
                    return new AuthorizationDecisionResponse(
                        e.getDecision(), e.getResponseCode(), e.getMessage());
                }
            }

            AuthorizationDecisionResponse decision = computeDecision(req);

            if (idemKey != null) {
                decision = persistOrReuse(idemKey, decision);
            }
            return decision;
        } finally {
            PartnerContext.clear();
        }
    }

    /** The core RC mapping + F1 scaling + F2 currency-aware limit. */
    private AuthorizationDecisionResponse computeDecision(AuthorizationDecisionRequest req) {
        Optional<Integer> exponent = currencyMinorUnits.exponentFor(req.currency());
        if (exponent.isEmpty()) {
            return decline("12", "Unknown currency");
        }

        Card card = cardRepo.findByPanHash(panHasher.hash(req.pan())).orElse(null);
        if (card == null) {
            return decline("56", "No such card");
        }
        if (card.getStatus() == CardStatus.BLOCKED || card.getStatus() == CardStatus.CANCELLED) {
            return decline("62", "Restricted");
        }
        if (card.getStatus() != CardStatus.ACTIVE) {
            return decline("54", "Card not usable");
        }

        BigDecimal amount = new BigDecimal(req.amountMinor()).movePointLeft(exponent.get());
        CardLimit lim = limitRepo.findByCardId(card.getId()).orElse(null);
        if (lim != null && lim.getPerTxn() != null) {
            if (!req.currency().equals(lim.getCurrencyCode())) {
                return decline("57", "Limit currency mismatch");
            }
            if (amount.compareTo(lim.getPerTxn()) > 0) {
                return decline("61", "Exceeds per-txn limit");
            }
        }

        return approve();
    }

    private AuthorizationDecisionResponse persistOrReuse(
            String idemKey, AuthorizationDecisionResponse decision) {
        // NOTE: decide() is not @Transactional, so save+re-read are separate statements.
        // The UNIQUE(idem_key) constraint guarantees a single row per key; on a concurrent
        // first-transmit the loser catches DataIntegrityViolationException and re-reads the
        // winner's committed row. Phase 2 should wrap decide() in @Transactional(REQUIRES_NEW)
        // when real balance holds make atomicity essential.
        try {
            idempotencyRepo.save(AuthorizationIdempotency.builder()
                .idemKey(idemKey)
                .decision(decision.decision())
                .responseCode(decision.responseCode())
                .message(decision.message())
                .reversed(false)
                .build());
            return decision;
        } catch (DataIntegrityViolationException race) {
            log.debug("idemKey race on {}, re-reading winner", idemKey);
            return idempotencyRepo.findByIdemKey(idemKey)
                .map(e -> new AuthorizationDecisionResponse(
                    e.getDecision(), e.getResponseCode(), e.getMessage()))
                .orElse(decision);
        }
    }

    private static String idemKey(AuthorizationDecisionRequest req) {
        if (isBlank(req.stan()) || isBlank(req.terminalId()) || isBlank(req.transmissionDateTime())) {
            return null;
        }
        // INVARIANT: ISO 8583 DE11 (STAN, numeric), DE41 (terminal, alphanumeric) and
        // DE7 (transmission date-time, numeric) never contain '|', so it is an
        // unambiguous separator. If a future FEP forwards a field that can contain '|',
        // switch to a fixed-length framing or a hash.
        return req.stan() + "|" + req.terminalId() + "|" + req.transmissionDateTime();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private AuthorizationDecisionResponse approve() {
        log.debug("Authorization decision: APPROVE rc=00");
        return new AuthorizationDecisionResponse("APPROVE", "00", "Approved");
    }

    private AuthorizationDecisionResponse decline(String responseCode, String message) {
        log.debug("Authorization decision: DECLINE rc={}", responseCode);
        return new AuthorizationDecisionResponse("DECLINE", responseCode, message);
    }
}
