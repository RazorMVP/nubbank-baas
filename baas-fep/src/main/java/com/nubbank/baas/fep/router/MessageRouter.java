package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * ISO 8583 message router — routes incoming requests to the correct handler
 * based on the Message Type Indicator (MTI).
 *
 * <p>Supported MTIs and their handlers:
 * <table border="1">
 *   <tr><th>MTI</th><th>Description</th><th>Handler</th></tr>
 *   <tr><td>{@code 0100}</td><td>Authorization Request</td><td>{@link AuthorizationHandler}</td></tr>
 *   <tr><td>{@code 0200}</td><td>Financial Transaction Request</td><td>{@link FinancialHandler}</td></tr>
 *   <tr><td>{@code 0400}</td><td>Reversal Request</td><td>{@link ReversalHandler}</td></tr>
 *   <tr><td>{@code 0800}</td><td>Network Management Request</td><td>{@link NetworkHandler}</td></tr>
 *   <tr><td>other</td><td>Unknown/unsupported MTI</td><td>RC 30 (format error)</td></tr>
 * </table>
 *
 * <p><strong>PAN safety:</strong> this router never logs any field values or raw bytes.
 */
@Component
@RequiredArgsConstructor
public class MessageRouter {

    private final AuthorizationHandler authHandler;
    private final FinancialHandler     financialHandler;
    private final ReversalHandler      reversalHandler;
    private final NetworkHandler       networkHandler;
    private final IsoMessageFactory    iso;

    /**
     * Route an incoming ISO 8583 request and return the appropriate response.
     *
     * <p>Unknown MTIs receive a response with DE39 = {@code "30"} (format error /
     * unsupported MTI), per ISO 8583 response code conventions.
     *
     * @param req the incoming message (packager already set by {@link IsoMessageFactory})
     * @return the outbound response message, ready to pack
     */
    public ISOMsg route(ISOMsg req) {
        String mti = mti(req);
        return switch (mti) {
            case "0100" -> authHandler.handle(req);
            case "0200" -> financialHandler.handle(req);
            case "0400" -> reversalHandler.handle(req);
            case "0800" -> networkHandler.handle(req);
            default     -> error(req, "30");   // format error / unsupported MTI
        };
    }

    /**
     * Build a system-error response ({@code 0810} / DE39 = {@code "96"}).
     *
     * <p>Called by {@link com.nubbank.baas.fep.server.FepMessageHandler} when the ISO
     * message cannot be parsed or an unexpected exception escapes the router.
     *
     * @return a minimal error response message
     */
    public ISOMsg systemError() {
        ISOMsg m = iso.create("0810");
        set(m, IsoField.RESPONSE_CODE, "96");
        return m;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Build an error response for a given request with the specified response code.
     *
     * @param req the original request (used to derive the response MTI)
     * @param rc  ISO 8583 response code, e.g. {@code "30"}
     * @return a response message with DE39 set to {@code rc}
     */
    private ISOMsg error(ISOMsg req, String rc) {
        ISOMsg m = iso.create(responseMti(mti(req)));
        set(m, IsoField.RESPONSE_CODE, rc);
        return m;
    }

    // ── Package-accessible static utilities (used by handler stubs) ──────────

    /**
     * Safely extract the MTI from a message, returning {@code "0000"} on failure.
     *
     * @param m the ISO 8583 message
     * @return the 4-character MTI string, or {@code "0000"} if unavailable
     */
    static String mti(ISOMsg m) {
        try {
            return m.getMTI();
        } catch (ISOException e) {
            return "0000";
        }
    }

    /**
     * Derive the response MTI from a request MTI by replacing the third character
     * with {@code '1'}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "0100"} → {@code "0110"}</li>
     *   <li>{@code "0200"} → {@code "0210"}</li>
     *   <li>{@code "0400"} → {@code "0410"}</li>
     *   <li>{@code "0800"} → {@code "0810"}</li>
     *   <li>{@code "0000"} → {@code "0010"} (fallback for unparseable MTI)</li>
     * </ul>
     *
     * @param reqMti 4-character request MTI
     * @return the corresponding 4-character response MTI
     */
    static String responseMti(String reqMti) {
        return reqMti.substring(0, 2) + "1" + reqMti.substring(3);
    }

    /**
     * Convenience wrapper around {@link ISOMsg#set(int, String)}.
     *
     * <p>{@code ISOMsg.set(int, String)} does NOT declare a checked exception.
     * This helper exists purely for uniform field-setting across handler methods.
     *
     * @param m     the message to modify
     * @param field DE field number
     * @param value field value
     */
    static void set(ISOMsg m, int field, String value) {
        // NOTE: ISOMsg.set(int, String) does NOT throw ISOException — no try/catch here.
        m.set(field, value);
    }
}
