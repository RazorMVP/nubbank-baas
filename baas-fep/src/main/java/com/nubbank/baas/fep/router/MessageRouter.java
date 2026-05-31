package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * ISO 8583 message router.
 *
 * <p><strong>TASK-3 MINIMAL STUB — TO BE REPLACED/EXTENDED BY THE FULL MTI ROUTER IN TASK 4.</strong>
 *
 * <p>This implementation handles only the {@code 0800} Network Management Request
 * (echo test, DE70=301) and returns {@code 0810} / DE39={@code 00}.  All other MTIs
 * should be added in Task 4 when the full 0100/0200/0400/0800 routing switch is built.
 *
 * <p>Keep this class minimal — do NOT pre-build 0100/0200/0400 handlers here (YAGNI).
 * Task 4 will replace the body of {@link #route(ISOMsg)} with the complete MTI switch.
 */
@Component
@RequiredArgsConstructor
public class MessageRouter {

    private final IsoMessageFactory iso;

    /**
     * Route an incoming ISO 8583 request and return a response.
     *
     * <p><em>Task-3 stub:</em> responds to {@code 0800} echo with {@code 0810}/RC={@code 00}.
     * Full MTI switch (0100, 0200, 0400, 0800) is added in Task 4.
     *
     * @param request the incoming message (packager already set by {@link IsoMessageFactory})
     * @return the outbound response message, ready to pack
     */
    public ISOMsg route(ISOMsg request) {
        // Task-3 stub: echo 0800 → 0810 / RC 00.
        // Full MTI switch added in Task 4 — this method body will be replaced.
        ISOMsg resp = iso.create("0810");
        set(resp, IsoField.RESPONSE_CODE, "00");

        // Mirror DE70 (Network Management Code) from request to response if present.
        // ISOMsg.set(int, String) does not throw ISOException; getString may return null.
        if (request.hasField(IsoField.NETWORK_MGMT_CODE)) {
            String nmCode = request.getString(IsoField.NETWORK_MGMT_CODE);
            if (nmCode != null) {
                resp.set(IsoField.NETWORK_MGMT_CODE, nmCode);
            }
        }

        return resp;
    }

    /**
     * Build a system-error response ({@code 0810} / DE39={@code 96}).
     * Called by {@link com.nubbank.baas.fep.server.FepMessageHandler} when the ISO
     * message cannot be parsed or an unexpected exception escapes the router.
     *
     * @return a minimal error response message
     */
    public ISOMsg systemError() {
        ISOMsg m = iso.create("0810");
        set(m, IsoField.RESPONSE_CODE, "96");
        return m;
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    /**
     * Convenience wrapper around {@link ISOMsg#set(int, String)}.
     * {@code ISOMsg.set(int, String)} does NOT declare a checked exception; this helper
     * exists purely for uniform field-setting across future Task-4 handler methods.
     */
    static void set(ISOMsg m, int field, String value) {
        m.set(field, value);
    }
}
