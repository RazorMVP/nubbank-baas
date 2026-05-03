package com.nubbank.baas.engine.accounting.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProvisioningCriteriaRequest(
    @NotBlank String name,
    @NotNull @Size(min = 1) List<DefinitionRequest> definitions
) {
    public record DefinitionRequest(
        @NotBlank String categoryName,
        @NotNull Integer minAge,
        @NotNull Integer maxAge,
        @NotNull @DecimalMin("0.0") BigDecimal provisionPercentage,
        UUID liabilityAccountId,
        UUID expenseAccountId
    ) {}
}
