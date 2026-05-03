package com.nubbank.baas.engine.deposit.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record FixedDepositRequest(
    @NotNull UUID customerId,
    @NotNull UUID productId,
    @NotNull @Positive BigDecimal depositAmount,
    @NotNull @Min(1) Integer depositTerm,
    String depositTermUnit,
    String currencyCode,
    UUID linkedAccountId
) {}
