package com.nubbank.baas.engine.share.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ShareProductResponse(
    UUID id,
    String name,
    String shortName,
    Long totalShares,
    Long sharesIssued,
    BigDecimal unitPrice,
    Integer minimumShares,
    Integer maximumShares,
    String currencyCode,
    boolean active,
    Instant createdAt
) {}
