package com.nubbank.baas.fep.client;

import com.nubbank.baas.fep.routing.AuthorizationDecision;
import com.nubbank.baas.fep.routing.PartnerRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HTTP-layer tests for {@link HttpCardClient} using {@link MockRestServiceServer} bound to the
 * real {@code cardRestTemplate} bean.
 *
 * <p>This exercises the production HTTP path that the interface-mocked handler/router tests cannot:
 * <ul>
 *   <li><strong>Envelope deserialization through Boot's configured {@code ObjectMapper}</strong> —
 *       the {@code ApiResponse} envelope carries a {@code meta.timestamp} as a JSR-310 {@link java.time.Instant}.
 *       Parsing it proves the JavaTime module is registered on the {@code RestTemplate}'s message converters
 *       (making that dependency explicit and regression-guarded rather than implicit).</li>
 *   <li><strong>Fail-closed / fail-safe contract</strong> — 404 and 5xx must degrade to
 *       {@code Optional.empty()} (BIN lookup) and a {@code DECLINE}/{@code 96} decision (authorize),
 *       never throwing into the calling (Netty) thread.</li>
 *   <li><strong>Outbound HMAC signing</strong> — the ported {@code SigningInterceptor} sets the
 *       {@code Authorization: Internal <hex>} and {@code X-Internal-Timestamp} headers.</li>
 *   <li><strong>PAN is forwarded in the authorize body</strong> (Card resolves the card by PAN hash).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class HttpCardClientTest {

    // application-test.yml: fep.card.base-url
    private static final String BASE = "http://localhost:0";

    private static final String KNOWN_BIN   = "50600012";
    private static final String KNOWN_PAN   = "5060001234567890";
    private static final UUID    PARTNER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String  SCHEMA     = "partner_acme";

    @Autowired
    @Qualifier("cardRestTemplate")
    private RestTemplate cardRestTemplate;

    @Autowired
    private HttpCardClient client;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.createServer(cardRestTemplate);
    }

    // ─────────────────────────── lookupBin ───────────────────────────────────

    @Test
    void lookupBin_success_deserializesEnvelopeIncludingInstantTimestamp() {
        // Full envelope with a JSR-310 Instant timestamp in meta — must deserialize cleanly.
        String body = """
            {
              "data": { "partnerId": "%s", "schemaName": "%s" },
              "meta": { "requestId": "req-1", "timestamp": "2026-06-02T12:00:00Z" },
              "errors": null
            }""".formatted(PARTNER_ID, SCHEMA);

        server.expect(requestTo(BASE + "/internal/v1/bins/" + KNOWN_BIN))
              .andExpect(method(org.springframework.http.HttpMethod.GET))
              // HMAC signing interceptor must have run.
              .andExpect(header("Authorization", startsWith("Internal ")))
              .andExpect(header("X-Internal-Timestamp", matchesPattern("\\d+")))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<PartnerRoute> route = client.lookupBin(KNOWN_BIN);

        assertThat(route).isPresent();
        assertThat(route.get().partnerId()).isEqualTo(PARTNER_ID);
        assertThat(route.get().schemaName()).isEqualTo(SCHEMA);
        server.verify();
    }

    @Test
    void lookupBin_404_returnsEmpty() {
        server.expect(requestTo(BASE + "/internal/v1/bins/" + KNOWN_BIN))
              .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.lookupBin(KNOWN_BIN)).isEmpty();
        server.verify();
    }

    @Test
    void lookupBin_serverError_failsClosedToEmpty() {
        server.expect(requestTo(BASE + "/internal/v1/bins/" + KNOWN_BIN))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Fail-closed: a 5xx must NOT throw into the caller; unrouteable.
        assertThat(client.lookupBin(KNOWN_BIN)).isEmpty();
        server.verify();
    }

    @Test
    void lookupBin_2xxButEmptyData_returnsEmpty() {
        String body = """
            { "data": null, "meta": { "requestId": "r", "timestamp": "2026-06-02T12:00:00Z" }, "errors": null }""";
        server.expect(requestTo(BASE + "/internal/v1/bins/" + KNOWN_BIN))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.lookupBin(KNOWN_BIN)).isEmpty();
        server.verify();
    }

    // ─────────────────────────── authorize ───────────────────────────────────

    @Test
    void authorize_success_returnsDecisionFromData_andForwardsPan() {
        String body = """
            {
              "data": { "decision": "APPROVE", "responseCode": "00", "message": "approved" },
              "meta": { "requestId": "req-2", "timestamp": "2026-06-02T12:00:01Z" },
              "errors": null
            }""";

        server.expect(requestTo(BASE + "/internal/v1/authorize"))
              .andExpect(method(org.springframework.http.HttpMethod.POST))
              .andExpect(header("Authorization", startsWith("Internal ")))
              // PAN + tenant context are forwarded to Card in the request body.
              .andExpect(jsonPath("$.pan").value(KNOWN_PAN))
              .andExpect(jsonPath("$.schemaName").value(SCHEMA))
              .andExpect(jsonPath("$.amountMinor").value(5000))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        AuthorizationDecision decision = client.authorize(new AuthorizationDecision.Request(
            PARTNER_ID.toString(), SCHEMA, KNOWN_PAN, 5000L, "566"));

        assertThat(decision.decision()).isEqualTo("APPROVE");
        assertThat(decision.responseCode()).isEqualTo("00");
        server.verify();
    }

    @Test
    void authorize_serverError_failsSafeToDecline96() {
        server.expect(requestTo(BASE + "/internal/v1/authorize"))
              .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AuthorizationDecision decision = client.authorize(new AuthorizationDecision.Request(
            PARTNER_ID.toString(), SCHEMA, KNOWN_PAN, 5000L, "566"));

        // Fail-safe: transport error → DECLINE / RC 96 (issuer unavailable), never an exception.
        assertThat(decision.decision()).isEqualTo("DECLINE");
        assertThat(decision.responseCode()).isEqualTo("96");
        server.verify();
    }

    @Test
    void authorize_2xxButEmptyData_returnsFailSafeDecline96() {
        String body = """
            { "data": null, "meta": { "requestId": "r", "timestamp": "2026-06-02T12:00:02Z" }, "errors": null }""";
        server.expect(requestTo(BASE + "/internal/v1/authorize"))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        AuthorizationDecision decision = client.authorize(new AuthorizationDecision.Request(
            PARTNER_ID.toString(), SCHEMA, KNOWN_PAN, 5000L, "566"));

        assertThat(decision.responseCode()).isEqualTo("96");
        server.verify();
    }
}
