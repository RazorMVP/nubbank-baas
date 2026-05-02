package com.nubbank.baas.engine.twofa.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record GenerateOtpRequest(
    @NotNull UUID userId,
    @NotBlank String deliveryMethod,
    @NotBlank String recipient
) {}
