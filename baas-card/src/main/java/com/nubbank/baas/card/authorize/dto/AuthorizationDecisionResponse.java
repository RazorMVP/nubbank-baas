package com.nubbank.baas.card.authorize.dto;

/**
 * FROZEN CROSS-TRACK CONTRACT §2a — field-for-field identical to the FEP's
 * {@code AuthorizationDecision.Response}.
 *
 * <p>{@code decision} is {@code "APPROVE"} or {@code "DECLINE"}; {@code responseCode}
 * is the ISO 8583 DE39 value ({@code 00} approve · {@code 56} no such card ·
 * {@code 62} blocked/cancelled · {@code 54} not usable · {@code 61} exceeds limit).
 *
 * <p>This is wrapped as the {@code data} payload of the standard {@code ApiResponse}
 * envelope on the wire — the FEP's {@code HttpCardClient} reads {@code .data}.
 */
public record AuthorizationDecisionResponse(
    String decision,
    String responseCode,
    String message
) {}
