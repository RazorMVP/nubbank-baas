package com.nubbank.baas.engine.charge.dto;

import com.nubbank.baas.engine.charge.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ChargeRequest(
    @NotBlank String name,
    @NotNull ChargeType chargeType,
    CalculationType calculationType,
    @NotNull @Positive BigDecimal amount,
    String currencyCode
) {}
