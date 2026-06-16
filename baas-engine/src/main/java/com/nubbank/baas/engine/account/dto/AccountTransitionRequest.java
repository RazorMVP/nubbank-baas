package com.nubbank.baas.engine.account.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountTransitionRequest(
    @NotBlank(message = "reason is required") String reason
) {}
