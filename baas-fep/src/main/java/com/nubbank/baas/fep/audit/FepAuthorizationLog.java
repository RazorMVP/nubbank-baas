package com.nubbank.baas.fep.audit;

import java.time.Instant;

/**
 * One authorization/reversal decision as recorded by the FEP (DEF-1C-24). Carries ONLY
 * {@code bin} (first 8) + {@code panLast4} — a truncated PAN, never the full DE2.
 */
public record FepAuthorizationLog(
    Instant receivedAt, String mti, String stan, String terminalId,
    String bin, String panLast4, String partnerId, String schemaName,
    Long amountMinor, String currency, String decision, String responseCode,
    boolean reversal, Integer latencyMs) {}
