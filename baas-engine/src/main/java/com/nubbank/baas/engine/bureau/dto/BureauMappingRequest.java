package com.nubbank.baas.engine.bureau.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BureauMappingRequest(@NotNull UUID loanProductId, Boolean creditCheckMandatory) {}
