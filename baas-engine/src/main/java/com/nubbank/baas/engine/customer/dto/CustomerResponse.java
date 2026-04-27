package com.nubbank.baas.engine.customer.dto;

import com.nubbank.baas.engine.customer.KycLevel;
import com.nubbank.baas.engine.customer.KycStatus;
import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
    UUID id,
    String externalReference,
    String firstName,
    String lastName,
    String email,
    KycStatus kycStatus,
    KycLevel kycLevel,
    Instant createdAt
) {}
