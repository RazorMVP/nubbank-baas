package com.nubbank.baas.fep.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Best-effort FEP authorization audit (DEF-1C-24). A write failure is logged and SWALLOWED —
 * it NEVER alters the ISO 8583 response. The authoritative money records live on the card +
 * engine (idempotency row, {@code card_auth_debit}, immutable {@code Transaction}); this is a
 * spine-side reconciliation/dispute aid, not the system of record.
 *
 * <p>Stores only {@code bin} + {@code panLast4} (a truncated PAN) — never the full PAN.
 */
@Service
public class AuthorizationAuditService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationAuditService.class);

    private final JdbcTemplate jdbc;

    public AuthorizationAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(FepAuthorizationLog e) {
        try {
            jdbc.update(
                "INSERT INTO fep.authorization_log " +
                "(id, received_at, mti, stan, terminal_id, bin, pan_last4, partner_id, schema_name, " +
                " amount_minor, currency, decision, response_code, reversal, latency_ms) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                e.receivedAt() == null ? null : Timestamp.from(e.receivedAt()),
                e.mti(), e.stan(), e.terminalId(), e.bin(), e.panLast4(),
                e.partnerId() == null ? null : UUID.fromString(e.partnerId()),
                e.schemaName(), e.amountMinor(), e.currency(), e.decision(),
                e.responseCode(), e.reversal(), e.latencyMs());
        } catch (Exception ex) {
            // Best-effort: never fail or alter the ISO response because of an audit write.
            log.warn("FEP audit write failed (swallowed): {}", ex.getMessage());
        }
    }
}
