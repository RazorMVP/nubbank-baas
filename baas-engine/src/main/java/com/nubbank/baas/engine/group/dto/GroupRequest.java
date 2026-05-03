package com.nubbank.baas.engine.group.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record GroupRequest(@NotBlank String name, String externalId,
    UUID officeId, UUID staffId) {}
