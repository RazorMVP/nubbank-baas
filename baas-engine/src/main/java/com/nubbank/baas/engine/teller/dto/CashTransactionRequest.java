package com.nubbank.baas.engine.teller.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record CashTransactionRequest(
    @NotBlank String transactionType, @NotNull @Positive BigDecimal amount,
    UUID accountId, String description
) {}
