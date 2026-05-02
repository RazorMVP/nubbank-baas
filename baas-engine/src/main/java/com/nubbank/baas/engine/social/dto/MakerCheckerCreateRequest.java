package com.nubbank.baas.engine.social.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record MakerCheckerCreateRequest(
    @NotBlank String entityType,
    UUID entityId,
    @NotBlank String action,
    @NotBlank String commandAsJson
) {}
