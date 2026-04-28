package com.nubbank.baas.engine.loan.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record ApplyLoanRequest(
    @NotNull UUID customerId, @NotNull UUID loanProductId,
    @NotNull @Positive BigDecimal principalAmount,
    @NotNull @Min(1) Integer numberOfRepayments,
    Integer repaymentEvery, String repaymentFrequency,
    UUID linkedAccountId, String currencyCode
) {}
