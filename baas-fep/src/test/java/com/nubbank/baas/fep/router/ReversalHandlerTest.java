package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.routing.ReversalDecision;
import com.nubbank.baas.fep.support.StubCardClient;
import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalHandlerTest {

    private static final String KNOWN_PAN   = "5060001234567890";
    private static final String KNOWN_BIN   = "50600012";
    private static final String UNKNOWN_PAN = "9999990000000000";
    private static final UUID   PARTNER_ID  = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String SCHEMA      = "partner_acme";

    private IsoMessageFactory iso;
    private StubCardClient    stub;
    private ReversalHandler   handler;

    @BeforeEach
    void setUp() {
        iso     = new IsoMessageFactory();
        stub    = new StubCardClient();
        stub.register(KNOWN_BIN, new PartnerRoute(PARTNER_ID, SCHEMA));
        handler = new ReversalHandler(new BinResolver(stub), stub, iso);
    }

    private ISOMsg build0400(String pan, String de90) {
        ISOMsg req = iso.create("0400");
        req.set(IsoField.PAN, pan);
        req.set(IsoField.STAN, "000009");
        req.set(IsoField.TRANSMISSION_DTS, "0101130000");
        req.set(IsoField.TERMINAL_ID, "TERM0001");
        if (de90 != null) req.set(IsoField.ORIGINAL_DATA, de90);
        return req;
    }

    // origMTI(0100) + origStan(000001) + origDateTime(0101120000) + acquirer(11 zeros) + forwarder(11 zeros) = 42 digits
    private static final String DE90 =
        "0100" + "000001" + "0101120000" + "00000000000" + "00000000000";

    @Test
    void originalLocated_returns0410_rc00() throws Exception {
        stub.withReversalResponse(new ReversalDecision(true));
        ISOMsg resp = handler.handle(build0400(KNOWN_PAN, DE90));
        assertThat(resp.getMTI()).isEqualTo("0410");
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("00");
        assertThat(resp.hasField(IsoField.PAN)).isFalse();
        assertThat(stub.lastReversalRequest().originalStan()).isEqualTo("000001");
        assertThat(stub.lastReversalRequest().originalTransmissionDateTime()).isEqualTo("0101120000");
        assertThat(stub.lastReversalRequest().terminalId()).isEqualTo("TERM0001");
    }

    @Test
    void originalNotLocated_returns0410_rc25() throws Exception {
        stub.withReversalResponse(new ReversalDecision(false));
        ISOMsg resp = handler.handle(build0400(KNOWN_PAN, DE90));
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("25");
    }

    @Test
    void unknownBin_returns0410_rc91_noPan() throws Exception {
        ISOMsg resp = handler.handle(build0400(UNKNOWN_PAN, DE90));
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("91");
        assertThat(resp.hasField(IsoField.PAN)).isFalse();
        assertThat(stub.lastReversalRequest()).isNull();
    }

    @Test
    void missingDe90_returns0410_rc30_noCardCall() throws Exception {
        ISOMsg resp = handler.handle(build0400(KNOWN_PAN, null));
        assertThat(resp.getString(IsoField.RESPONSE_CODE)).isEqualTo("30");
        assertThat(stub.lastReversalRequest()).isNull();
    }
}
