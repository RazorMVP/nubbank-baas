package com.nubbank.baas.engine.share.dto;

import com.nubbank.baas.engine.share.ShareTransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ShareTransactionResponse(
    UUID id,
    UUID accountId,
    ShareTransactionType transactionType,
    Long numberOfShares,
    BigDecimal unitPrice,
    BigDecimal totalAmount,
    Instant createdAt
) {}
