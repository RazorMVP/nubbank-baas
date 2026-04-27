package com.nubbank.baas.engine.payment.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID destinationAccountId,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    String currencyCode,
    String reference,
    String description,
    String idempotencyKey
) {}
