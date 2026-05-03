package com.nubbank.baas.engine.rate.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaxGroupRequest(
    @NotBlank String name,
    @NotNull @Size(min = 1) List<MappingRequest> components
) {
    public record MappingRequest(@NotNull UUID componentId, @NotNull LocalDate startDate) {}
}
