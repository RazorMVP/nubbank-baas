package com.nubbank.baas.engine.teller.dto;

import jakarta.validation.constraints.NotBlank;

public record TellerRequest(@NotBlank String name, String description) {}
