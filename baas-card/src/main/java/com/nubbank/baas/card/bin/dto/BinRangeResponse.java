package com.nubbank.baas.card.bin.dto;

import com.nubbank.baas.card.bin.CardBinRange;

import java.util.UUID;

public record BinRangeResponse(
    UUID id,
    String binStart,
    String binEnd,
    String scheme,
    boolean active
) {
    public static BinRangeResponse from(CardBinRange b) {
        return new BinRangeResponse(b.getId(), b.getBinStart(), b.getBinEnd(), b.getScheme(), b.isActive());
    }
}
