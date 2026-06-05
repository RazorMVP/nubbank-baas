package com.nubbank.baas.card.engine.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Card→engine debit request. {@code currency} is ISO 4217 ALPHABETIC (card-translated). */
public record CardDebitRequest(String partnerId, String schemaName, UUID accountId,
                               String authKey, BigDecimal amount, String currency) {}
