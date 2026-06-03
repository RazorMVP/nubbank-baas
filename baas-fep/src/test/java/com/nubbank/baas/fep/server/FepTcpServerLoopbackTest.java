package com.nubbank.baas.fep.server;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.support.Iso8583TestClient;
import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end loopback test: starts the full Spring context with {@code fep.tcp-port: 0}
 * (ephemeral port), opens a real TCP socket, sends a length-prefixed ISO 8583 {@code 0800}
 * Network Management Request (DE70=301 echo test), reads the length-prefixed response,
 * and asserts MTI {@code 0810} with DE39 {@code 00}.
 *
 * <p>This test exercises the complete FEP pipeline:
 * <pre>
 *   Iso8583TestClient ──TCP──► FepTcpServer (Netty)
 *                               └─► LengthFieldBasedFrameDecoder (strip 2-byte header)
 *                               └─► FepMessageHandler (unpack → route → pack)
 *                               └─► MessageRouter (0800 → 0810 / RC=00)
 *                               └─► LengthFieldPrepender (prepend 2-byte header)
 *                            ◄──TCP── Iso8583TestClient
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FepTcpServerLoopbackTest {

    @Autowired
    FepTcpServer fepTcpServer;

    @Autowired
    IsoMessageFactory iso;

    @Test
    void echo0800_returns0810_withResponseCode00() throws Exception {
        // ── Build a 0800 Network Management Request (echo, DE70=301) ────────
        ISOMsg request = iso.create("0800");
        request.set(IsoField.STAN,               "000042");  // DE11 – Systems Trace Audit Number
        request.set(IsoField.NETWORK_MGMT_CODE,  "301");     // DE70 – echo test

        byte[] payload = iso.pack(request);

        // ── Open a real TCP socket to the ephemeral bound port ───────────────
        int port = fepTcpServer.getBoundPort();
        assertThat(port).isGreaterThan(0);

        try (Iso8583TestClient client = new Iso8583TestClient("127.0.0.1", port)) {
            // ── Send length-prefixed request, receive length-prefixed reply ──
            byte[] responseBytes = client.send(payload);

            // ── Unpack and assert MTI + response code ────────────────────────
            ISOMsg response = iso.unpack(responseBytes);

            assertThat(response.getMTI())
                    .as("MTI must be 0810 (Network Management Response)")
                    .isEqualTo("0810");

            assertThat(response.getString(IsoField.RESPONSE_CODE))
                    .as("DE39 must be '00' (Approved)")
                    .isEqualTo("00");
        }
    }
}
