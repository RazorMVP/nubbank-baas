package com.nubbank.baas.engine.share.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ShareTransactionRequest(
    @NotNull @Positive Long numberOfShares
) {}
