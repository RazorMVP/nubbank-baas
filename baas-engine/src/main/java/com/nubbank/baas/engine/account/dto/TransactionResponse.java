package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID id,
    UUID accountId,
    TransactionType transactionType,
    BigDecimal amount,
    BigDecimal runningBalance,
    String currencyCode,
    String reference,
    Instant createdAt
) {}
