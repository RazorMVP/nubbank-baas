package com.nubbank.baas.engine.office.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.UUID;

public record OfficeRequest(@NotBlank String name, UUID parentId,
    LocalDate openingDate, String externalId) {}
