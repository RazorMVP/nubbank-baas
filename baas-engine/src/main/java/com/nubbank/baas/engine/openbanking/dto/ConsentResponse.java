package com.nubbank.baas.engine.openbanking.dto;

import com.nubbank.baas.engine.openbanking.ConsentStatus;
import java.time.*;
import java.util.List;
import java.util.UUID;

public record ConsentResponse(
    UUID id, String tppClientId, String tppName,
    ConsentStatus status, List<String> scopes,
    LocalDate expiryDate, String accessFrequency,
    Instant authorisedAt, Instant revokedAt, Instant createdAt
) {}
