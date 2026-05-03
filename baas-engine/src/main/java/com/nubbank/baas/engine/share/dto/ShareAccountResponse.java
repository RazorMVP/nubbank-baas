package com.nubbank.baas.engine.share.dto;

import com.nubbank.baas.engine.share.ShareAccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ShareAccountResponse(
    UUID id,
    UUID customerId,
    UUID productId,
    String accountNumber,
    Long totalSharesHeld,
    BigDecimal totalAmount,
    ShareAccountStatus status,
    String currencyCode,
    Instant createdAt
) {}
