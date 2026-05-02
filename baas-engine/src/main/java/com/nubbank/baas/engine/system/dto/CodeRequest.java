package com.nubbank.baas.engine.system.dto;

import jakarta.validation.constraints.NotBlank;

public record CodeRequest(@NotBlank String name) {}
