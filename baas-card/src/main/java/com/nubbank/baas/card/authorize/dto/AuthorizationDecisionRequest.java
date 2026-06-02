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
 * <p>SECURITY: {@code pan} is the full PAN — it MUST NEVER be logged. This record has
 * no overridden {@code toString()} reference in any log line for that reason.
 */
public record AuthorizationDecisionRequest(
    String partnerId,
    String schemaName,
    String pan,
    long amountMinor,
    String currency
) {}
