package com.nubbank.baas.engine.product.dto;

import com.nubbank.baas.engine.product.RepaymentType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record LoanProductRequest(
    @NotBlank String name,
    @NotBlank @Size(max = 10) String shortName,
    String description,
    @NotNull @Positive BigDecimal minPrincipal,
    @NotNull @Positive BigDecimal maxPrincipal,
    @NotNull @Positive BigDecimal defaultPrincipal,
    @NotNull @DecimalMin("0.0") BigDecimal nominalInterestRate,
    RepaymentType repaymentType,
    @NotNull @Min(1) Integer numberOfRepayments,
    Integer repaymentEvery,
    String repaymentFrequency
) {}
