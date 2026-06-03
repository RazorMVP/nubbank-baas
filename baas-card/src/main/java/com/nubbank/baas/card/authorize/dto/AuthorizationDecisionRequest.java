package com.nubbank.baas.card.authorize.dto;

/**
 * FROZEN CROSS-TRACK CONTRACT §2a — field-for-field identical to the FEP's
 * {@code AuthorizationDecision.Request}.
 *
 * <p>The FEP (over HMAC) is the only caller. It resolved the tenant via the BIN
 * lookup, so it passes {@code schemaName} (which tenant schema to route to) and the
 * full {@code pan} (ISO 8583 DE2 — the ONLY card identifier the FEP has; the stub
 * resolves the card by deterministic {@code pan_hash}, NOT by id).
 *
 * <p>{@code amountMinor} is in minor units (e.g. kobo/cents). {@code currency} is the
 * ISO 4217 numeric code (e.g. {@code "566"} for NGN), as it appears in DE49.
 *
 * <p>ISO trace fields (added Phase 1C): {@code stan} is the Systems Trace Audit Number
 * (DE11, 6 digits); {@code terminalId} is the terminal identifier (DE41, 8 chars);
 * {@code transmissionDateTime} is the transmission date/time (DE7, {@code MMDDhhmmss}).
 * These are used by downstream tasks for idempotency deduplication.
 *
 * <p>SECURITY: {@code pan} is the full PAN — it MUST NEVER be logged. This record has
 * no overridden {@code toString()} reference in any log line for that reason.
 */
public record AuthorizationDecisionRequest(
    String partnerId,
    String schemaName,
    String pan,
    long amountMinor,
    String currency,
    String stan,                 // ISO 8583 DE11
    String terminalId,           // ISO 8583 DE41
    String transmissionDateTime  // ISO 8583 DE7 (MMDDhhmmss)
) {}
