package com.nubbank.baas.engine.rate.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TaxComponentRequest(
    @NotBlank String name,
    @NotNull @DecimalMin("0.0") BigDecimal percentage,
    @NotNull LocalDate startDate,
    UUID creditAccountId,
    UUID debitAccountId
) {}
