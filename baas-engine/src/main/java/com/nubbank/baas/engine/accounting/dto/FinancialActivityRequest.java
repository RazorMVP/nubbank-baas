package com.nubbank.baas.engine.accounting.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record FinancialActivityRequest(@NotBlank String activityName, @NotNull UUID glAccountId) {}
