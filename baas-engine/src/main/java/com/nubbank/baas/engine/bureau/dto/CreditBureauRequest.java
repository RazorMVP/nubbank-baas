package com.nubbank.baas.engine.bureau.dto;

import jakarta.validation.constraints.NotBlank;

public record CreditBureauRequest(@NotBlank String name, @NotBlank String implClass, String country) {}
