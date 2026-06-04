package com.nubbank.baas.card.authorize.dto;

/**
 * Result of a reversal lookup: whether the original authorization was located (and is
 * now marked reversed). The FEP maps {@code located=true} → RC 00, false → RC 25.
 */
public record ReversalResponse(boolean located) {}
