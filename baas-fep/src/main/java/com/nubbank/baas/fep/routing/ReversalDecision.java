package com.nubbank.baas.fep.routing;

/**
 * Result of a reversal call to the Card service (F6).
 *
 * @param located whether the original authorization was found (and is now reversed).
 *                The handler maps {@code true} → RC 00, {@code false} → RC 25.
 */
public record ReversalDecision(boolean located) {

    /** Request body sent to {@code POST /internal/v1/reversal}. */
    public record Request(
        String partnerId,
        String schemaName,
        String originalStan,
        String terminalId,
        String originalTransmissionDateTime
    ) {}
}
