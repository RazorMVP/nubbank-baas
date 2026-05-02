package com.nubbank.baas.engine.rate.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FloatingRateRequest(
    @NotBlank String name,
    Boolean isBaseLendingRate,
    @NotNull @Size(min = 1) List<PeriodRequest> periods
) {
    public record PeriodRequest(
        @NotNull LocalDate fromDate,
        @NotNull BigDecimal interestRate,
        Boolean isDifferentialToBaseLending
    ) {}
}
