package com.nubbank.baas.engine.customer.dto;

import jakarta.validation.constraints.NotBlank;

public record KycTransitionRequest(
    @NotBlank(message = "reason is required") String reason
) {}
