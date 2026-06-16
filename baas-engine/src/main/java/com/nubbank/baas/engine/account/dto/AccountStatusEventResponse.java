package com.nubbank.baas.engine.account.dto;

import java.time.Instant;
import java.util.UUID;

public record AccountStatusEventResponse(
    UUID id,
    String fromStatus,
    String toStatus,
    String reason,
    String changedBy,
    Instant changedAt
) {}
