package com.nubbank.baas.engine.product.dto;

import com.nubbank.baas.engine.product.AccountType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record DepositProductRequest(
    @NotBlank String name,
    @NotBlank @Size(max = 10) String shortName,
    AccountType accountType,
    @DecimalMin("0.0") BigDecimal minimumBalance,
    @DecimalMin("0.0") BigDecimal nominalInterestRate,
    boolean allowOverdraft,
    BigDecimal overdraftLimit
) {}
