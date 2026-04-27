package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    UUID id,
    UUID customerId,
    String accountNumber,
    String accountTypeLabel,
    AccountStatus status,
    BigDecimal balance,
    BigDecimal availableBalance,
    String currencyCode,
    Instant createdAt
) {}
