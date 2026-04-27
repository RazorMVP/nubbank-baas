package com.nubbank.baas.engine.account.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record OpenAccountRequest(
    @NotNull(message = "customerId is required") UUID customerId,
    String accountTypeLabel,
    String accountName,
    @Pattern(regexp = "[A-Z]{3}", message = "currencyCode must be 3-letter ISO code")
    String currencyCode,
    BigDecimal minimumBalance
) {}
