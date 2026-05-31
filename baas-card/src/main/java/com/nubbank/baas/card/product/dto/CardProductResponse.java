package com.nubbank.baas.card.product.dto;

import com.nubbank.baas.card.card.CardType;
import com.nubbank.baas.card.product.CardProduct;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CardProductResponse(
    UUID id,
    String name,
    CardType cardType,
    String currency,
    String binStart,
    BigDecimal defaultDailyLimit,
    boolean active,
    Instant createdAt
) {
    public static CardProductResponse from(CardProduct p) {
        return new CardProductResponse(
            p.getId(), p.getName(), p.getCardType(), p.getCurrency(),
            p.getBinStart(), p.getDefaultDailyLimit(), p.isActive(), p.getCreatedAt());
    }
}
