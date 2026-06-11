package com.nubbank.baas.engine.customer.dto;

import com.nubbank.baas.engine.customer.KycLevel;
import com.nubbank.baas.engine.customer.KycStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerDetailResponse(
    UUID id,
    String externalReference,
    String firstName,
    String lastName,
    String email,
    String phone,
    LocalDate dateOfBirth,
    String gender,
    String bvnMasked,
    String ninMasked,
    KycStatus kycStatus,
    KycLevel kycLevel,
    Instant createdAt,
    Instant updatedAt
) {}
