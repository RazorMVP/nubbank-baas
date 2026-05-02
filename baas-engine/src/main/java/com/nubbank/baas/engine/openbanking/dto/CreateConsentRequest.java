package com.nubbank.baas.engine.openbanking.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateConsentRequest(
    @NotBlank String tppClientId,
    String tppName,
    @NotNull @Size(min = 1) List<String> scopes,
    LocalDate expiryDate,
    String accessFrequency,
    UUID customerId
) {}
