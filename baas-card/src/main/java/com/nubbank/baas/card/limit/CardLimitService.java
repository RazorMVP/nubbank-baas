package com.nubbank.baas.card.limit;

import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.limit.dto.CardLimitResponse;
import com.nubbank.baas.card.limit.dto.UpdateCardLimitsRequest;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-card limits, tenant-scoped.
 *
 * <p>Mirrors the {@code CardService} / {@code CardProductService} idiom: every entry
 * point calls {@link #requireContext()} first, then operates inside the authenticated
 * partner's schema (Hibernate routes automatically — NO partnerId column or filter;
 * the schema IS the isolation boundary). The card is loaded within the current tenant
 * schema, so partner B cannot set/read limits on partner A's card — a missing card is
 * a 404 CARD_NOT_FOUND (not 403), to avoid enumeration.
 *
 * <p>PUT semantics are REPLACE: the request body fully replaces the limit row — a
 * field absent (null) from the body becomes null on the row (clears that limit).
 *
 * <p>Validation: every provided (non-null) amount must be {@code >= 0}, and
 * {@code perTxn <= dailyPurchase} when BOTH are set. Either violation → a single
 * {@code 400 INVALID_LIMITS} (done HERE in the service, not via bean-validation
 * annotations, so the error code is consistent across both cases).
 */
@Service
@RequiredArgsConstructor
public class CardLimitService {

    private final CardLimitRepository limitRepository;
    private final CardRepository cardRepository;

    @Transactional
    public CardLimitResponse updateLimits(UUID cardId, UpdateCardLimitsRequest req) {
        requireContext();
        requireCardExists(cardId);
        validate(req);

        // Upsert by cardId: load the existing row (the card_id UNIQUE constraint
        // guarantees at most one) or create a fresh one. REPLACE semantics — every
        // field, including absent (null) ones, is written, so the second PUT clears
        // any limit it omits.
        CardLimit limit = limitRepository.findByCardId(cardId)
            .orElseGet(() -> CardLimit.builder().cardId(cardId).build());
        limit.setDailyPurchase(req.dailyPurchase());
        limit.setDailyWithdrawal(req.dailyWithdrawal());
        limit.setPerTxn(req.perTxn());
        limit.setMonthly(req.monthly());

        return CardLimitResponse.from(limitRepository.save(limit));
    }

    @Transactional(readOnly = true)
    public CardLimitResponse getLimits(UUID cardId) {
        requireContext();
        requireCardExists(cardId);

        // Limits are optional config: a card with no limit row yet is "unlimited".
        // Return 200 with an all-null view rather than 404 LIMITS_NOT_FOUND.
        return limitRepository.findByCardId(cardId)
            .map(CardLimitResponse::from)
            .orElseGet(() -> CardLimitResponse.forCard(cardId));
    }

    // ---- internals ----

    /**
     * The card must exist in the CURRENT tenant schema. Loading it here (within the
     * partner's schema) is the tenant-isolation gate: partner B's schema has no such
     * card → 404 CARD_NOT_FOUND (not 403), preventing cross-tenant enumeration.
     */
    private void requireCardExists(UUID cardId) {
        if (!cardRepository.existsById(cardId)) {
            throw BaasException.notFound("CARD_NOT_FOUND", "Card " + cardId + " not found");
        }
    }

    /** Sign check (all non-null amounts >= 0) + cross-field (perTxn <= dailyPurchase). */
    private void validate(UpdateCardLimitsRequest req) {
        requireNonNegative(req.dailyPurchase(), "dailyPurchase");
        requireNonNegative(req.dailyWithdrawal(), "dailyWithdrawal");
        requireNonNegative(req.perTxn(), "perTxn");
        requireNonNegative(req.monthly(), "monthly");

        if (req.perTxn() != null && req.dailyPurchase() != null
            && req.perTxn().compareTo(req.dailyPurchase()) > 0) {
            throw BaasException.badRequest("INVALID_LIMITS",
                "perTxn (" + req.perTxn() + ") must not exceed dailyPurchase ("
                    + req.dailyPurchase() + ")");
        }
    }

    private void requireNonNegative(BigDecimal amount, String field) {
        if (amount != null && amount.signum() < 0) {
            throw BaasException.badRequest("INVALID_LIMITS",
                field + " must be >= 0 (was " + amount + ")");
        }
    }

    private void requireContext() {
        if (PartnerContext.get() == null) {
            throw BaasException.unauthorized("MISSING_AUTH",
                "Authorization header required — use ApiKey or Bearer JWT");
        }
    }
}
