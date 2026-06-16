package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.AccountStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountSummaryResponse(
    UUID id,
    String accountNumber,
    UUID customerId,
    String customerName,
    String accountTypeLabel,
    AccountStatus status,
    BigDecimal balance,
    String currencyCode
) {}
