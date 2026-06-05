package com.nubbank.baas.card.engine.dto;

/** Card→engine account-existence lookup result. {@code exists=false} on not-found OR unreachable. */
public record AccountLookupResult(boolean exists, boolean active, String currencyCode) {}
