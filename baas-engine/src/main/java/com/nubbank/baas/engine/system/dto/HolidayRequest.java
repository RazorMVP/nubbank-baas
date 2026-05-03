package com.nubbank.baas.engine.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record HolidayRequest(
    @NotBlank String name,
    @NotNull LocalDate fromDate,
    @NotNull LocalDate toDate,
    String repaymentSchedulingType,
    String description
) {}
