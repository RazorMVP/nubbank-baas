package com.nubbank.baas.engine.clientext.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record IdentifierRequest(@NotBlank String documentType, @NotBlank String documentKey,
    String description, LocalDate expiryDate) {}
