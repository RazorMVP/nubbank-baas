package com.nubbank.baas.card.limit.dto;

import java.math.BigDecimal;

/**
 * Request body for {@code PUT /baas/v1/cards/{id}/limits}.
 *
 * <p>All four amounts are optional/nullable — a partner may set only some. PUT has
 * REPLACE semantics: a field absent (null) from the body becomes null on the row
 * (i.e. clears that limit → unlimited).
 *
 * <p>NOTE on validation: the sign check ({@code >= 0}) and the cross-field check
 * ({@code perTxn <= dailyPurchase}) are done in {@code CardLimitService}, NOT via
 * bean-validation annotations. This is deliberate — the spec requires the single
 * error code {@code INVALID_LIMITS} for BOTH a negative amount and the cross-field
 * violation. A {@code @PositiveOrZero} annotation would surface a negative as
 * {@code VALIDATION_ERROR} instead, diverging from the contract. Centralising the
 * checks in the service keeps the error code consistent.
 */
public record UpdateCardLimitsRequest(
    BigDecimal dailyPurchase,
    BigDecimal dailyWithdrawal,
    BigDecimal perTxn,
    BigDecimal monthly
) {}
