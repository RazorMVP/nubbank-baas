package com.nubbank.baas.engine.makerchecker.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfigUpdateRequest(@NotBlank String commandType, boolean enabled) {}
