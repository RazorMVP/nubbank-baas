package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.audit.AuthorizationAuditService;
import com.nubbank.baas.fep.audit.FepAuthorizationLog;
import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.AuthorizationDecision;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.routing.CardClient;
import com.nubbank.baas.fep.routing.PartnerRoute;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Handles ISO 8583 Authorization Requests (MTI {@code 0100}).
 *
 * <p>Flow:
 * <ol>
 *   <li>Extract DE2 (PAN) and resolve to a {@link com.nubbank.baas.fep.routing.PartnerRoute}
 *       via {@link BinResolver}.</li>
 *   <li>If no route → return {@code 0110} with DE39={@code "91"} (issuer/BIN unrouteable).
 *       <strong>DE2 (PAN) is intentionally omitted from the response.</strong></li>
 *   <li>If route found → call {@link CardClient#authorize} with the full transaction context.</li>
 *   <li>Build {@code 0110} response with DE39 from the decision; set DE38 (auth code) only on
 *       {@code "00"} approval.</li>
 * </ol>
 *
 * <p><strong>PAN safety invariants (enforced at all times):</strong>
 * <ul>
 *   <li>The PAN is NEVER logged (not at DEBUG, INFO, WARN, or ERROR).</li>
 *   <li>DE2 is NEVER set on any outbound response message — not on approval, decline, or error.</li>
 *   <li>{@link #echo(ISOMsg, ISOMsg, int...)} only copies explicitly listed DE fields;
 *       DE2 is never passed to it.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AuthorizationHandler {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BinResolver  binResolver;
    private final CardClient   cardClient;
    private final IsoMessageFactory iso;
    private final AuthorizationAuditService auditService;

    /**
     * Handle an incoming ISO 8583 authorization or financial request.
     *
     * <p>The response MTI is derived from the request MTI via
     * {@link MessageRouter#responseMti(String)}, so a {@code 0100} produces {@code 0110}
     * and a {@code 0200} produces {@code 0210} (used by the delegating {@link FinancialHandler}).
     *
     * @param req the incoming request (0100 or 0200)
     * @return the outbound response, ready to pack
     */
    public ISOMsg handle(ISOMsg req) {
        Instant start = Instant.now();
        String pan   = field(req, IsoField.PAN);
        var    route = binResolver.resolve(pan);

        if (route.isEmpty()) {
            audit(req, pan, null, null, "91", start);   // best-effort; never blocks the response
            return unrouteable(req);   // RC 91, DE2 deliberately absent
        }

        Long amount = parseAmount(field(req, IsoField.AMOUNT));
        if (amount == null) {
            audit(req, pan, route.get(), null, "30", start);
            return formatError(req);   // RC 30 — DE4 absent or non-numeric (no Card call)
        }

        AuthorizationDecision decision = cardClient.authorize(new AuthorizationDecision.Request(
            route.get().partnerId().toString(),
            route.get().schemaName(),
            pan,                          // forwarded to Card — NEVER logged here
            amount,
            field(req, IsoField.CURRENCY),
            field(req, IsoField.STAN),
            field(req, IsoField.TERMINAL_ID),
            field(req, IsoField.TRANSMISSION_DTS)
        ));

        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        echo(req, resp,
            IsoField.STAN,
            IsoField.TRANSMISSION_DTS,
            IsoField.PROC_CODE,
            IsoField.AMOUNT,
            IsoField.CURRENCY,
            IsoField.TERMINAL_ID);
        // DE2 (PAN) is intentionally NOT in the echo list — never set on responses.

        MessageRouter.set(resp, IsoField.RESPONSE_CODE, decision.responseCode());

        if ("00".equals(decision.responseCode())) {
            MessageRouter.set(resp, IsoField.AUTH_CODE, authCode());
        }

        audit(req, pan, route.get(), decision, decision.responseCode(), start);
        return resp;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Records the decision to the FEP audit log (DEF-1C-24) — best-effort, never throws.
     * Derives {@code bin} (first 8) + {@code panLast4} locally; the full PAN is NEVER logged
     * or persisted. {@code route}/{@code decision} may be null on the unrouteable / format-error
     * paths.
     */
    private void audit(ISOMsg req, String pan, PartnerRoute route,
                       AuthorizationDecision decision, String rc, Instant start) {
        String bin = pan == null ? null : pan.substring(0, Math.min(8, pan.length()));
        String last4 = (pan == null || pan.length() < 4) ? null : pan.substring(pan.length() - 4);
        auditService.record(new FepAuthorizationLog(
            start, MessageRouter.mti(req), field(req, IsoField.STAN), field(req, IsoField.TERMINAL_ID),
            bin, last4,
            route == null ? null : route.partnerId().toString(),
            route == null ? null : route.schemaName(),
            parseAmount(field(req, IsoField.AMOUNT)),
            field(req, IsoField.CURRENCY),
            decision == null ? null : decision.decision(), rc, false,
            (int) Duration.between(start, Instant.now()).toMillis()));
    }

    /**
     * Build an unrouteable response (RC {@code "91"} — issuer/BIN unavailable).
     *
     * <p>Only STAN and TRANSMISSION_DTS are echoed so the terminal can correlate the
     * response to its request.  <strong>DE2 (PAN) is deliberately excluded from the
     * response to avoid PAN leakage for unknown BINs.</strong>
     */
    private ISOMsg unrouteable(ISOMsg req) {
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        // Echo only correlation fields — DE2 (PAN) is intentionally NOT included.
        echo(req, resp, IsoField.STAN, IsoField.TRANSMISSION_DTS);
        MessageRouter.set(resp, IsoField.RESPONSE_CODE, "91");
        return resp;
    }

    /**
     * Build a format-error response (RC {@code "30"} — invalid/malformed message).
     *
     * <p>Used when a routed request is missing or has a non-numeric DE4 (amount). No Card call
     * is made. Like every response path, DE2 (PAN) is never set; only correlation fields are echoed.
     */
    private ISOMsg formatError(ISOMsg req) {
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        echo(req, resp, IsoField.STAN, IsoField.TRANSMISSION_DTS);
        MessageRouter.set(resp, IsoField.RESPONSE_CODE, "30");
        return resp;
    }

    /**
     * Parse a DE4 amount string into minor units, or {@code null} if absent / non-numeric.
     *
     * <p>Returning {@code null} (rather than throwing) lets the caller map the condition to a
     * semantically correct ISO 8583 format error (RC 30) instead of a generic system error (RC 96).
     *
     * @param raw the DE4 field value (may be {@code null})
     * @return parsed amount in minor units, or {@code null} if it cannot be parsed
     */
    private static Long parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;   // never propagate — caller returns RC 30
        }
    }

    /**
     * Returns the value of the requested field, or {@code null} if the field is absent.
     *
     * @param m     the ISO message
     * @param field DE field number
     * @return field value string, or {@code null}
     */
    private static String field(ISOMsg m, int field) {
        return m.hasField(field) ? m.getString(field) : null;
    }

    /**
     * Copy only the listed fields from {@code req} to {@code resp}, skipping absent fields.
     *
     * <p>DE2 (PAN) is NEVER passed to this method — callers must not add it to the field list.
     *
     * @param req    source request message
     * @param resp   destination response message
     * @param fields varargs list of DE field numbers to copy (must not include DE2)
     */
    private static void echo(ISOMsg req, ISOMsg resp, int... fields) {
        for (int f : fields) {
            if (req.hasField(f)) {
                resp.set(f, req.getString(f));
            }
        }
    }

    /**
     * Generate a 6-digit authorization code using a cryptographically secure random source.
     *
     * @return zero-padded 6-digit string, e.g. {@code "042317"}
     */
    private static String authCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
