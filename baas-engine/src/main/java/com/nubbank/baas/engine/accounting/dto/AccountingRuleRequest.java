package com.nubbank.baas.engine.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record AccountingRuleRequest(
    @NotBlank String name,
    UUID debitAccountId,
    UUID creditAccountId,
    Boolean allowMultipleDebits,
    Boolean allowMultipleCredits
) {}
