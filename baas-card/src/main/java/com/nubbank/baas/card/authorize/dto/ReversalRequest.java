package com.nubbank.baas.card.authorize.dto;

/**
 * Internal reversal lookup — FROZEN CROSS-TRACK CONTRACT (F6). The FEP composes the
 * ORIGINAL authorization's idempotency key from DE90 (original STAN + original
 * transmission date-time) and DE41 (terminal), and asks card to mark it reversed.
 */
public record ReversalRequest(
    String partnerId,
    String schemaName,
    String originalStan,
    String terminalId,
    String originalTransmissionDateTime
) {}
