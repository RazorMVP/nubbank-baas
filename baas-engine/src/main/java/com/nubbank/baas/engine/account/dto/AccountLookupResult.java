package com.nubbank.baas.engine.account.dto;

/** Internal account-existence lookup result. */
public record AccountLookupResult(boolean exists, boolean active, String currencyCode) {}
