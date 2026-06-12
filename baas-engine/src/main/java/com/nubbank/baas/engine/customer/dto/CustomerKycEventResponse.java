package com.nubbank.baas.engine.customer.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerKycEventResponse(
    UUID id,
    String fromStatus,
    String toStatus,
    String reason,
    String changedBy,
    Instant changedAt
) {}
