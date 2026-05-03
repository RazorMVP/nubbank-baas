package com.nubbank.baas.engine.accounting.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record GlClosureRequest(@NotNull LocalDate closingDate, String description) {}
