package com.nubbank.baas.engine.share.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ShareAccountRequest(
    @NotNull UUID customerId,
    @NotNull UUID productId
) {}
