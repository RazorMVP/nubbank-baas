package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.support.StubCardClient;
import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NetworkHandler} and MTI routing in {@link MessageRouter}.
 *
 * <p>No Spring context — all objects constructed directly via {@code new}, using the
 * real {@link IsoMessageFactory}. No mocks.
 *
 * <p>As of Task 6 the auth/financial/reversal handlers are fully implemented, so the
 * routing assertions for 0100/0200/0400 verify the real (not stub-RC-96) behaviour.
 * A {@link StubCardClient} with no registered BINs is used so unknown-BIN → RC 91 is
 * the expected outcome for the bare requests sent here (no PAN / unregistered PAN).
 */
class NetworkHandlerTest {

    private IsoMessageFactory factory;
    private NetworkHandler    networkHandler;
    private MessageRouter     router;

    @BeforeEach
    void setUp() {
        factory        = new IsoMessageFactory();
        networkHandler = new NetworkHandler(factory);

        // Full router with real handlers. StubCardClient has no BINs registered so
        // auth/financial requests without a known PAN yield RC 91 (unrouteable).
        StubCardClient       stub             = new StubCardClient();
        BinResolver          binResolver      = new BinResolver(stub);
        AuthorizationHandler authHandler      = new AuthorizationHandler(binResolver, stub, factory);
        FinancialHandler     financialHandler  = new FinancialHandler(authHandler);
        ReversalHandler      reversalHandler   = new ReversalHandler(factory);
        router = new MessageRouter(authHandler, financialHandler, reversalHandler, networkHandler, factory);
    }

    // ── NetworkHandler direct tests ──────────────────────────────────────────

    @Test
    void handle_0800_sign_on_returns0810_RC00_withDE70Echoed() throws Exception {
        // 0800 sign-on (DE70 = 001)
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "001");

        ISOMsg resp = networkHandler.handle(req);

        assertThat(resp.getMTI())
                .as("response MTI must be 0810")
                .isEqualTo("0810");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("DE39 must be 00")
                .isEqualTo("00");
        assertThat(resp.getString(IsoField.NETWORK_MGMT_CODE))
                .as("DE70 must be echoed from request")
                .isEqualTo("001");
    }

    @Test
    void handle_0800_echo_returns0810_RC00_withDE70Echoed() throws Exception {
        // 0800 echo test (DE70 = 301) — same as Task-3 loopback scenario
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "301");

        ISOMsg resp = networkHandler.handle(req);

        assertThat(resp.getMTI()).isEqualTo("0810");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");
        assertThat(resp.getString(IsoField.NETWORK_MGMT_CODE)).isEqualTo("301");
    }

    @Test
    void handle_0800_echoesDe11Stan_whenPresent() throws Exception {
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "001");
        req.set(IsoField.STAN,              "000123");

        ISOMsg resp = networkHandler.handle(req);

        assertThat(resp.getString(IsoField.STAN))
                .as("DE11 (STAN) must be echoed")
                .isEqualTo("000123");
    }

    @Test
    void handle_0800_echoesDe7TransmissionDts_whenPresent() throws Exception {
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "001");
        req.set(IsoField.TRANSMISSION_DTS,  "0101120000");

        ISOMsg resp = networkHandler.handle(req);

        assertThat(resp.getString(IsoField.TRANSMISSION_DTS))
                .as("DE7 (transmission date/time) must be echoed")
                .isEqualTo("0101120000");
    }

    @Test
    void handle_0800_echoesDe11AndDe7Together() throws Exception {
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "001");
        req.set(IsoField.STAN,              "000123");
        req.set(IsoField.TRANSMISSION_DTS,  "0101120000");

        ISOMsg resp = networkHandler.handle(req);

        assertThat(resp.getMTI()).isEqualTo("0810");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");
        assertThat(resp.getString(IsoField.NETWORK_MGMT_CODE)).isEqualTo("001");
        assertThat(resp.getString(IsoField.STAN)).isEqualTo("000123");
        assertThat(resp.getString(IsoField.TRANSMISSION_DTS)).isEqualTo("0101120000");
    }

    @Test
    void handle_0800_de11AbsentInRequest_notPresentInResponse() throws Exception {
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "301");
        // DE11 deliberately omitted

        ISOMsg resp = networkHandler.handle(req);

        assertThat(resp.hasField(IsoField.STAN))
                .as("DE11 must NOT be present in response when absent in request")
                .isFalse();
    }

    // ── MessageRouter routing tests ──────────────────────────────────────────

    @Test
    void router_0800_routes_to_networkHandler_returns0810_RC00() throws Exception {
        ISOMsg req = factory.create("0800");
        req.set(IsoField.NETWORK_MGMT_CODE, "001");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI()).isEqualTo("0810");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");
        assertThat(resp.getString(IsoField.NETWORK_MGMT_CODE)).isEqualTo("001");
    }

    @Test
    void router_unknownMti_returns_RC30() throws Exception {
        // 0999 is not a known MTI — router must return RC 30 (format error / unsupported)
        ISOMsg req = factory.create("0999");

        ISOMsg resp = router.route(req);

        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("unknown MTI must yield RC 30 (format error)")
                .isEqualTo("30");
    }

    @Test
    void router_0100_routes_to_authHandler_unknownBin_returns_RC91() throws Exception {
        // No PAN set → unknown BIN → AuthorizationHandler returns RC 91 (unrouteable).
        ISOMsg req = factory.create("0100");
        req.set(IsoField.PROC_CODE, "000000");
        req.set(IsoField.AMOUNT,    "000000010000");
        req.set(IsoField.STAN,      "000001");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI())
                .as("0100 request must yield 0110 response MTI")
                .isEqualTo("0110");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("no registered BIN → RC 91 (unrouteable)")
                .isEqualTo("91");
    }

    @Test
    void router_0200_routes_to_financialHandler_unknownBin_returns_RC91() throws Exception {
        // No PAN set → unknown BIN → delegated AuthorizationHandler returns RC 91.
        ISOMsg req = factory.create("0200");
        req.set(IsoField.PROC_CODE, "010000");
        req.set(IsoField.AMOUNT,    "000000020000");
        req.set(IsoField.STAN,      "000002");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI())
                .as("0200 request must yield 0210 response MTI")
                .isEqualTo("0210");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("no registered BIN → RC 91 (unrouteable)")
                .isEqualTo("91");
    }

    @Test
    void router_0400_routes_to_reversalHandler_returns_RC00() throws Exception {
        // ReversalHandler is a Phase-1C stub that approves all reversals.
        ISOMsg req = factory.create("0400");
        req.set(IsoField.STAN, "000003");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI())
                .as("0400 request must yield 0410 response MTI")
                .isEqualTo("0410");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("reversal stub always approves with RC 00")
                .isEqualTo("00");
    }

    @Test
    void router_systemError_returns0810_RC96() throws Exception {
        ISOMsg resp = router.systemError();

        assertThat(resp.getMTI()).isEqualTo("0810");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("96");
    }
}
