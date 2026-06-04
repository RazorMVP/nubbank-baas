package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.AuthorizationDecision;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.support.StubCardClient;
import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link AuthorizationHandler} — no Spring context needed.
 *
 * <p>All three routing outcomes are covered:
 * <ol>
 *   <li>Known BIN + Card returns APPROVE/00 → 0110 with DE39=00, DE38 present, correct echo fields,
 *       and the authorize request forwarded to Card carries the right schemaName/partnerId/amount/currency/pan.</li>
 *   <li>Known BIN + Card returns DECLINE/61 → 0110 with DE39=61, DE38 absent.</li>
 *   <li>Unknown BIN → 0110 with DE39=91, DE2 (PAN) ABSENT — security invariant.</li>
 * </ol>
 *
 * <p>Additionally exercises {@link FinancialHandler} delegation (0200 → 0210).
 */
class AuthorizationHandlerTest {

    // PAN whose normalized 8-char BIN is "50600012"
    private static final String KNOWN_PAN   = "5060001234567890";
    private static final String KNOWN_BIN   = "50600012";
    // PAN whose BIN is not registered → triggers unrouteable path
    private static final String UNKNOWN_PAN = "9999990000000000";

    private static final UUID   PARTNER_ID  = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String SCHEMA_NAME = "partner_acme";

    private IsoMessageFactory iso;
    private StubCardClient    stub;
    private BinResolver       binResolver;
    private AuthorizationHandler handler;

    @BeforeEach
    void setUp() {
        iso        = new IsoMessageFactory();
        stub       = new StubCardClient();
        stub.register(KNOWN_BIN, new PartnerRoute(PARTNER_ID, SCHEMA_NAME));
        binResolver = new BinResolver(stub);
        handler     = new AuthorizationHandler(binResolver, stub, iso);
    }

    // ─────────────────── helper: build a standard 0100 request ───────────────

    private ISOMsg buildRequest(String mti, String pan, String amount) {
        ISOMsg req = iso.create(mti);
        req.set(IsoField.PAN,              pan);
        req.set(IsoField.PROC_CODE,        "000000");
        req.set(IsoField.AMOUNT,           amount);
        req.set(IsoField.TRANSMISSION_DTS, "0101120000");
        req.set(IsoField.STAN,             "000001");
        req.set(IsoField.CURRENCY,         "566");
        req.set(IsoField.TERMINAL_ID,      "TERM0001");
        return req;
    }

    // ─────────────────────── APPROVE path ────────────────────────────────────

    @Test
    void knownBin_cardApproves_returns0110_de39_00_de38_present() throws Exception {
        stub.withAuthorizeResponse(new AuthorizationDecision("APPROVE", "00", "approved"));
        ISOMsg req  = buildRequest("0100", KNOWN_PAN, "000000005000");
        ISOMsg resp = handler.handle(req);

        // MTI must be 0110
        assertThat(resp.getMTI()).isEqualTo("0110");

        // RC 00 — approved
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");

        // Auth code must be present, non-blank, and exactly 6 digits
        assertThat(resp.hasField(IsoField.AUTH_CODE)).isTrue();
        assertThat(resp.getString(IsoField.AUTH_CODE)).matches("\\d{6}");

        // PAN must NEVER be present in the authorization response (no PAN echo, even on approval)
        assertThat(resp.hasField(IsoField.PAN)).isFalse();

        // STAN and TRANSMISSION_DTS must be echoed from the request
        assertThat(resp.getString(IsoField.STAN)).isEqualTo("000001");
        assertThat(resp.getString(IsoField.TRANSMISSION_DTS)).isEqualTo("0101120000");
    }

    @Test
    void knownBin_cardApproves_forwardsCorrectRequestToCard() {
        stub.withAuthorizeResponse(new AuthorizationDecision("APPROVE", "00", "approved"));
        ISOMsg req = buildRequest("0100", KNOWN_PAN, "000000005000");
        handler.handle(req);

        AuthorizationDecision.Request captured = stub.lastAuthorizeRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.schemaName()).isEqualTo(SCHEMA_NAME);
        assertThat(captured.partnerId()).isEqualTo(PARTNER_ID.toString());
        assertThat(captured.pan()).isEqualTo(KNOWN_PAN);
        assertThat(captured.amountMinor()).isEqualTo(5000L);
        assertThat(captured.currency()).isEqualTo("566");
        assertThat(captured.stan()).isEqualTo("000001");
        assertThat(captured.terminalId()).isEqualTo("TERM0001");
        assertThat(captured.transmissionDateTime()).isEqualTo("0101120000");
    }

    // ─────────────────────── DECLINE path ────────────────────────────────────

    @Test
    void knownBin_cardDeclines_returns0110_de39_61_noDE38() throws Exception {
        stub.withAuthorizeResponse(new AuthorizationDecision("DECLINE", "61", "limit exceeded"));
        ISOMsg req  = buildRequest("0100", KNOWN_PAN, "000000005000");
        ISOMsg resp = handler.handle(req);

        assertThat(resp.getMTI()).isEqualTo("0110");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("61");

        // PAN must NEVER be present in the authorization response (no PAN echo, even on decline)
        assertThat(resp.hasField(IsoField.PAN)).isFalse();

        // Auth code must NOT be present on a decline
        assertThat(resp.hasField(IsoField.AUTH_CODE)).isFalse();
    }

    // ─────────────────── FORMAT ERROR (routed but bad DE4) path ──────────────

    @Test
    void knownBin_missingAmount_returns0110_de39_30_noCardCall_noPAN() throws Exception {
        ISOMsg req = buildRequest("0100", KNOWN_PAN, "000000005000");
        req.unset(IsoField.AMOUNT);                 // DE4 absent on a routed request
        ISOMsg resp = handler.handle(req);

        assertThat(resp.getMTI()).isEqualTo("0110");
        // RC 30 — format error (NOT RC 96 system error) for a malformed/absent amount
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("30");

        // PAN must never be echoed, and no auth code on a format error
        assertThat(resp.hasField(IsoField.PAN)).isFalse();
        assertThat(resp.hasField(IsoField.AUTH_CODE)).isFalse();

        // Card must NOT be called when the request fails format validation
        assertThat(stub.lastAuthorizeRequest()).isNull();

        // Correlation fields still echoed
        assertThat(resp.getString(IsoField.STAN)).isEqualTo("000001");
        assertThat(resp.getString(IsoField.TRANSMISSION_DTS)).isEqualTo("0101120000");
    }

    @Test
    void knownBin_nonNumericAmount_returns0110_de39_30_noCardCall() throws Exception {
        ISOMsg req = buildRequest("0100", KNOWN_PAN, "000000005000");
        req.set(IsoField.AMOUNT, "00ABCD00");       // non-numeric DE4 on a routed request
        ISOMsg resp = handler.handle(req);

        assertThat(resp.getMTI()).isEqualTo("0110");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("30");
        assertThat(stub.lastAuthorizeRequest()).isNull();
    }

    // ─────────────────── UNKNOWN BIN (unrouteable) path ──────────────────────

    @Test
    void unknownBin_returns0110_de39_91_noPAN_noDE38() throws Exception {
        ISOMsg req  = buildRequest("0100", UNKNOWN_PAN, "000000002000");
        ISOMsg resp = handler.handle(req);

        assertThat(resp.getMTI()).isEqualTo("0110");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("91");

        // ★ SECURITY INVARIANT: PAN must NEVER be echoed in an unrouteable response ★
        assertThat(resp.hasField(IsoField.PAN)).isFalse();

        // Auth code must NOT be present either
        assertThat(resp.hasField(IsoField.AUTH_CODE)).isFalse();

        // Card service must NOT have been called for an unrouteable request
        assertThat(stub.lastAuthorizeRequest()).isNull();
    }

    @Test
    void unknownBin_echoesStanAndTransmissionDts() throws Exception {
        ISOMsg req  = buildRequest("0100", UNKNOWN_PAN, "000000002000");
        ISOMsg resp = handler.handle(req);

        // STAN and TRANSMISSION_DTS should still be echoed so the terminal can correlate
        assertThat(resp.getString(IsoField.STAN)).isEqualTo("000001");
        assertThat(resp.getString(IsoField.TRANSMISSION_DTS)).isEqualTo("0101120000");
    }

    // ─────────────────── FinancialHandler delegation (0200 → 0210) ───────────

    @Test
    void financialHandler_delegates_produces0210() throws Exception {
        stub.withAuthorizeResponse(new AuthorizationDecision("APPROVE", "00", "approved"));
        FinancialHandler financial = new FinancialHandler(handler);

        ISOMsg req  = buildRequest("0200", KNOWN_PAN, "000000001000");
        ISOMsg resp = financial.handle(req);

        // Delegation must produce 0210 (not 0110)
        assertThat(resp.getMTI()).isEqualTo("0210");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");
        assertThat(resp.hasField(IsoField.AUTH_CODE)).isTrue();
    }

}
