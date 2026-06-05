package com.nubbank.baas.card.engine.dto;

/** Card→engine credit result. {@code located=false} on not-found OR engine-unreachable (fail-closed). */
public record CardCreditResult(boolean located) {}
