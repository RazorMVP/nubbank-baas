package com.nubbank.baas.engine.standing.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record StandingInstructionRequest(
    @NotNull UUID customerId,
    @NotNull UUID sourceAccountId,
    @NotNull UUID destinationAccountId,
    @NotBlank String name,
    String instructionType,
    String priority,
    BigDecimal amount,
    String recurrenceFrequency,
    Integer recurrenceInterval,
    LocalDate validFrom,
    LocalDate validTo
) {}
