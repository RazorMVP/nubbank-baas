package com.nubbank.baas.engine.system.dto;

import jakarta.validation.constraints.NotBlank;

public record CodeValueRequest(@NotBlank String value, String description, Integer position) {}
