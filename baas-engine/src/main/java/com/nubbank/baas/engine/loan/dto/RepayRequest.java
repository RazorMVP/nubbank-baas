package com.nubbank.baas.engine.loan.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record RepayRequest(@NotNull @Positive BigDecimal amount) {}
