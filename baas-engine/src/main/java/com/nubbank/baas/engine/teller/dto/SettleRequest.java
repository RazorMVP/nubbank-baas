package com.nubbank.baas.engine.teller.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SettleRequest(@NotNull @DecimalMin("0.0") BigDecimal actualCash) {}
