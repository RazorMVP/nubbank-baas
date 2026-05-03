package com.nubbank.baas.engine.charge.dto;

import com.nubbank.baas.engine.charge.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChargeResponse(
    UUID id, String name, ChargeType chargeType, CalculationType calculationType,
    BigDecimal amount, String currencyCode, boolean active, Instant createdAt
) {}
