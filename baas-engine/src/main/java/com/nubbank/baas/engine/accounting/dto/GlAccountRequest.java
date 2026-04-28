package com.nubbank.baas.engine.accounting.dto;

import com.nubbank.baas.engine.accounting.GlAccountType;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record GlAccountRequest(
    @NotBlank String name,
    @NotBlank String glCode,
    @NotNull GlAccountType accountType,
    String accountUsage,
    UUID parentId,
    Boolean manualJournalEntriesAllowed,
    String description
) {}
