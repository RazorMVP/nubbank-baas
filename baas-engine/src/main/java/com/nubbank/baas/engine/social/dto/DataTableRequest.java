package com.nubbank.baas.engine.social.dto;

import jakarta.validation.constraints.NotBlank;

public record DataTableRequest(
    @NotBlank String registeredTableName,
    @NotBlank String applicationTableName,
    Boolean allowMultipleRows
) {}
