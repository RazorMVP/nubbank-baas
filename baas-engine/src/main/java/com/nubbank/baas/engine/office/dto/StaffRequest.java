package com.nubbank.baas.engine.office.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.UUID;

public record StaffRequest(@NotBlank String firstName, @NotBlank String lastName,
    UUID officeId, Boolean isLoanOfficer, String externalId, LocalDate joiningDate) {}
