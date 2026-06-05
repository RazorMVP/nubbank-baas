package com.nubbank.baas.engine.account.dto;

/** Internal card-credit (reversal) result. The card maps {@code located} → DE39 00 / 25. */
public record CardCreditResult(boolean located) {}
