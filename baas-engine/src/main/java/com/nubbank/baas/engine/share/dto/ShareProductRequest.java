package com.nubbank.baas.engine.share.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ShareProductRequest(
    @NotBlank String name,
    @NotBlank @Size(max = 10) String shortName,
    String description,
    @NotNull @Positive Long totalShares,
    @NotNull @Positive BigDecimal unitPrice,
    @Min(1) Integer minimumShares,
    Integer maximumShares,
    String currencyCode
) {}
