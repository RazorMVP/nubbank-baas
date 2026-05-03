package com.nubbank.baas.engine.deposit.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record RecurringDepositRequest(
    @NotNull UUID customerId,
    @NotNull UUID productId,
    @NotNull @Positive BigDecimal mandatoryInstallment,
    @NotNull @Min(1) Integer depositTerm,
    String depositTermUnit,
    String currencyCode,
    UUID linkedAccountId
) {}
