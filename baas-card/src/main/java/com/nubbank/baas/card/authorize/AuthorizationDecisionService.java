package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionResponse;
import com.nubbank.baas.card.card.Card;
import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.card.CardStatus;
import com.nubbank.baas.card.card.PanHasher;
import com.nubbank.baas.card.limit.CardLimit;
import com.nubbank.baas.card.limit.CardLimitRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Internal card-authorization-decision stub — FROZEN CROSS-TRACK CONTRACT §2a.
 *
 * <p>The caller is the FEP over HMAC. It already resolved the tenant via the BIN
 * lookup and passes {@code schemaName + PAN}. This service is THE ONE place baas-card
 * sets {@link PartnerContext} itself (every other entry point reads a context the
 * auth filters established): the FEP is a tenant-less internal caller, so we set the
 * resolved tenant here and route Hibernate to the partner's schema.
 *
 * <p>CRITICAL INVARIANT — the {@code set} is paired with an UNCONDITIONAL
 * {@code finally { PartnerContext.clear(); }}. A leaked ThreadLocal here would route
 * the NEXT request on this pooled thread to the wrong tenant — a cross-tenant data
 * breach. The clear MUST fire even if the lookup throws.
 *
 * <p>The card is resolved by {@code findByPanHash(panHasher.hash(pan))} — NOT by id;
 * the FEP only has the PAN (ISO 8583 DE2). RC mapping (Phase 1C stub):
 * <ul>
 *   <li>{@code 00} — APPROVE (ACTIVE card, within per-txn limit; balance stub)</li>
 *   <li>{@code 56} — no such card (no pan_hash match in this tenant)</li>
 *   <li>{@code 62} — restricted (BLOCKED or CANCELLED)</li>
 *   <li>{@code 54} — not usable (ISSUED / EXPIRED)</li>
 *   <li>{@code 61} — exceeds per-txn limit</li>
 * </ul>
 * Balance is a STUB (always sufficient — real balance comes from baas-engine in
 * Phase 2).
 *
 * <p>SECURITY: the full PAN ({@code req.pan()}) is NEVER logged. Only the decision /
 * responseCode are logged.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionService.class);

    private final CardRepository cardRepo;
    private final CardLimitRepository limitRepo;
    private final PanHasher panHasher;

    public AuthorizationDecisionResponse decide(AuthorizationDecisionRequest req) {
        // The FEP resolved the tenant via the BIN lookup and passes schemaName + PAN.
        // PartnerContext component order: partnerId, schemaName, tier, environment, authMode, userId.
        PartnerContext.set(new PartnerContext(
            req.partnerId(), req.schemaName(), "PRO", "PRODUCTION", "INTERNAL", null));
        try {
            // FEP only has the PAN (ISO 8583 DE2) — resolve by deterministic pan_hash, not id.
            Card card = cardRepo.findByPanHash(panHasher.hash(req.pan())).orElse(null);
            if (card == null) {
                return decline("56"); // no such card
            }
            if (card.getStatus() == CardStatus.BLOCKED || card.getStatus() == CardStatus.CANCELLED) {
                return decline("62"); // restricted
            }
            if (card.getStatus() != CardStatus.ACTIVE) {
                return decline("54"); // not usable (ISSUED / EXPIRED)
            }

            // amountMinor is in minor units → /100 gives the major-unit amount to compare
            // against perTxn, which is stored in major units (NUMERIC(19,4)).
            BigDecimal amount = new BigDecimal(req.amountMinor()).movePointLeft(2);
            CardLimit lim = limitRepo.findByCardId(card.getId()).orElse(null);
            if (lim != null && lim.getPerTxn() != null
                && amount.compareTo(lim.getPerTxn()) > 0) {
                return decline("61"); // exceeds per-txn limit
            }

            // Phase 1C: balance check is a stub (always sufficient). Real balance via
            // baas-engine in Phase 2.
            return approve();
        } finally {
            // MANDATORY: unconditional clear — fires even if the lookup above throws.
            PartnerContext.clear();
        }
    }

    private AuthorizationDecisionResponse approve() {
        log.debug("Authorization decision: APPROVE rc=00");
        return new AuthorizationDecisionResponse("APPROVE", "00", "Approved");
    }

    private AuthorizationDecisionResponse decline(String responseCode) {
        // Log only the decision/responseCode — NEVER the PAN.
        log.debug("Authorization decision: DECLINE rc={}", responseCode);
        return new AuthorizationDecisionResponse("DECLINE", responseCode, "Declined");
    }
}
