package com.nubbank.baas.engine.compliance;

import java.util.UUID;

public record SanctionsScreeningResult(UUID entityId, String entityType,
    String screenType, String result, String provider, String notes) {}
