package com.nubbank.baas.card.bin.dto;

import java.util.UUID;

/**
 * FROZEN FEP contract §2: the internal lookup response shape carried inside the
 * ApiResponse {@code data} — {@code { partnerId, schemaName }}.
 */
public record BinLookupResponse(
    UUID partnerId,
    String schemaName
) {}
