package com.nubbank.baas.engine.loan.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CollateralRequest(
    @NotBlank String description,
    @NotNull @Positive BigDecimal value,
    String currencyCode
) {}
