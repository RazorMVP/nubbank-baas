package com.nubbank.baas.engine.account.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record TransactionRequest(
    @NotNull @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    BigDecimal amount,
    String reference,
    String description
) {}
