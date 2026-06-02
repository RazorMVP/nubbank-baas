package com.nubbank.baas.card.limit.dto;

import com.nubbank.baas.card.limit.CardLimit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-card limits response.
 *
 * <p>A null amount means "no limit" (unlimited) for that dimension. When no limit
 * row exists for a card yet, {@link #forCard(UUID)} returns an all-null view
 * (limits are optional config; absent = unlimited).
 */
public record CardLimitResponse(
    UUID cardId,
    BigDecimal dailyPurchase,
    BigDecimal dailyWithdrawal,
    BigDecimal perTxn,
    BigDecimal monthly,
    Instant updatedAt
) {
    public static CardLimitResponse from(CardLimit l) {
        return new CardLimitResponse(
            l.getCardId(),
            l.getDailyPurchase(),
            l.getDailyWithdrawal(),
            l.getPerTxn(),
            l.getMonthly(),
            l.getUpdatedAt());
    }

    /** All-null view for a card that has no limit row set yet (absent = unlimited). */
    public static CardLimitResponse forCard(UUID cardId) {
        return new CardLimitResponse(cardId, null, null, null, null, null);
    }
}
