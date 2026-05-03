package com.nubbank.baas.engine.standing.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record BeneficiaryRequest(@NotBlank String accountNumber, String accountName,
    String bankCode, String bankName, BigDecimal transferLimit) {}
