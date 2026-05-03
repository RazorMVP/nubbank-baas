package com.nubbank.baas.engine.teller.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record OpenSessionRequest(
    @NotNull UUID cashierId, @NotNull @DecimalMin("0.0") BigDecimal openingBalance, String currencyCode
) {}
