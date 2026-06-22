package com.nubbank.baas.engine.partner.key.dto;

import java.util.UUID;

/** The raw {@code apiKey} value is shown exactly once at issuance and never retrievable again. */
public record IssuedApiKeyResponse(UUID id, String keyPrefix, String apiKey) {}
