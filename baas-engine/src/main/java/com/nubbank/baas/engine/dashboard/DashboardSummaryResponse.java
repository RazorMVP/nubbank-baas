package com.nubbank.baas.engine.dashboard;

import java.math.BigDecimal;

/**
 * Operations-console summary tiles (DEF-1C-29), scoped to the authenticated partner's schema.
 *
 * <p>{@code cardsIssued} is {@code Long} (nullable): cards live in a separate service, so a
 * card-service outage degrades this single tile to null rather than failing the whole call.
 */
public record DashboardSummaryResponse(
    long totalCustomers,
    long kycPendingCustomers,
    long totalAccounts,
    long activeAccounts,
    BigDecimal totalDeposits,
    long totalLoans,
    long activeLoans,
    Long cardsIssued
) {}
