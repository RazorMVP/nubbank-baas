package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NetworkHandler} and MTI routing in {@link MessageRouter}.
 *
 * <p>No Spring context — all objects constructed directly via {@code new}, using the
 * real {@link IsoMessageFactory}. No mocks.
 */
class NetworkHandlerTest {

    private IsoMessageFactory factory;
    private NetworkHandler    networkHandler;
    private MessageRouter     router;

    @BeforeEach
    void setUp() {
        factory       = new IsoMessageFactory();
        networkHandler = new NetworkHandler(factory);

        // Full router with the real NetworkHandler + three stub handlers.
        AuthorizationHandler authHandler      = new AuthorizationHandler(factory);
        FinancialHandler     financialHandler  = new FinancialHandler(factory);
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
    void router_0100_routes_to_authStub_returns_RC96() throws Exception {
        // AuthorizationHandler is a stub in Task 4 — must return RC 96 placeholder
        ISOMsg req = factory.create("0100");
        req.set(IsoField.PROC_CODE, "000000");
        req.set(IsoField.AMOUNT,    "000000010000");
        req.set(IsoField.STAN,      "000001");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI())
                .as("0100 request must yield 0110 response MTI")
                .isEqualTo("0110");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("stub returns RC 96")
                .isEqualTo("96");
    }

    @Test
    void router_0200_routes_to_financialStub_returns_RC96() throws Exception {
        ISOMsg req = factory.create("0200");
        req.set(IsoField.PROC_CODE, "010000");
        req.set(IsoField.AMOUNT,    "000000020000");
        req.set(IsoField.STAN,      "000002");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI())
                .as("0200 request must yield 0210 response MTI")
                .isEqualTo("0210");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("stub returns RC 96")
                .isEqualTo("96");
    }

    @Test
    void router_0400_routes_to_reversalStub_returns_RC96() throws Exception {
        ISOMsg req = factory.create("0400");
        req.set(IsoField.STAN, "000003");

        ISOMsg resp = router.route(req);

        assertThat(resp.getMTI())
                .as("0400 request must yield 0410 response MTI")
                .isEqualTo("0410");
        assertThat(resp.getString(IsoField.RESPONSE_CODE))
                .as("stub returns RC 96")
                .isEqualTo("96");
    }

    @Test
    void router_systemError_returns0810_RC96() throws Exception {
        ISOMsg resp = router.systemError();

        assertThat(resp.getMTI()).isEqualTo("0810");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("96");
    }
}
