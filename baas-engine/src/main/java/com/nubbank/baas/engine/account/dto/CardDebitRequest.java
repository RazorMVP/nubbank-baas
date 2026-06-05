package com.nubbank.baas.engine.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal card-debit request body (Stage 5). {@code currency} is ISO 4217 ALPHABETIC
 * (the card translates the DE49 numeric code before calling). {@code amount} is in major
 * units. {@code authKey} = {@code stan|terminalId|transmissionDateTime}.
 */
public record CardDebitRequest(
    String partnerId, String schemaName, UUID accountId,
    String authKey, BigDecimal amount, String currency) {}
