package com.nubbank.baas.engine.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
    @NotBlank(message = "orgName is required") String orgName,
    @Email(message = "Valid email required") @NotBlank String adminEmail,
    @Size(min = 8, message = "Password must be at least 8 characters") @NotBlank String password
) {}
