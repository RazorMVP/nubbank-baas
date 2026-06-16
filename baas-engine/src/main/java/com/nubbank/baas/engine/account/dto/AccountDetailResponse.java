package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountDetailResponse(
    UUID id,
    String accountNumber,
    UUID customerId,
    String customerName,
    String accountTypeLabel,
    AccountStatus status,
    BigDecimal balance,
    BigDecimal availableBalance,
    String currencyCode,
    BigDecimal minimumBalance,
    boolean allowOverdraft,
    BigDecimal overdraftLimit,
    Instant openedAt
) {}
