package com.nubbank.baas.card.product.dto;

import com.nubbank.baas.card.card.CardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create-card-product body. There is NO partnerId/schemaName field — the tenant is
 * taken from the authenticated PartnerContext (the schema is the isolation
 * boundary), never the request body.
 *
 * {@code cardType} is bound as the enum directly (Jackson maps {@code "DEBIT"} →
 * {@link CardType#DEBIT}). {@code currency} must be exactly 3 chars to match the
 * ISO 4217 column length.
 */
public record CreateCardProductRequest(
    @NotBlank(message = "name is required") String name,
    @NotNull(message = "cardType is required") CardType cardType,
    @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code") String currency,
    String binStart,
    BigDecimal defaultDailyLimit
) {}
