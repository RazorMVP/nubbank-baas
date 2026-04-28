package com.nubbank.baas.engine.accounting.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryRequest(
    @NotNull LocalDate entryDate,
    String reference,
    String description,
    @NotNull @Size(min = 2) List<LineRequest> lines
) {
    public record LineRequest(
        @NotNull UUID glAccountId,
        @NotBlank String entryType,
        @NotNull @Positive BigDecimal amount,
        String currencyCode
    ) {}
}
