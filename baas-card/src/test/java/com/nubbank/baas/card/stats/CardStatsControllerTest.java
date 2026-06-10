package com.nubbank.baas.card.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.card.Card;
import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.card.CardStatus;
import com.nubbank.baas.card.tenant.PartnerContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-1C-29 (card side) — {@code POST /internal/v1/stats} returns the count of cards issued
 * in the partner schema, for the engine's dashboard aggregate. HMAC-guarded like every
 * {@code /internal/v1/**} endpoint; an unsigned call is 401.
 */
class CardStatsControllerTest extends AbstractCardIntegrationTest {

    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";
    private static final String PROVISION = "/internal/v1/provision";
    private static final String STATS = "/internal/v1/stats";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private CardRepository cardRepository;

    @Test
    void signedStats_countsCardsInPartnerSchema() throws Exception {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        String partnerId = UUID.randomUUID().toString();
        // Provision the card schema objects, then seed two cards into it.
        postSigned(PROVISION, Map.of("partnerId", partnerId, "schemaName", schema));
        seedCard(partnerId, schema, "1111");
        seedCard(partnerId, schema, "2222");

        ResponseEntity<Map> resp = postSigned(STATS, Map.of("partnerId", partnerId, "schemaName", schema));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(((Number) data.get("cardsIssued")).longValue()).isEqualTo(2);
    }

    @Test
    void signedStats_emptySchema_returnsZero() throws Exception {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        String partnerId = UUID.randomUUID().toString();
        postSigned(PROVISION, Map.of("partnerId", partnerId, "schemaName", schema));

        ResponseEntity<Map> resp = postSigned(STATS, Map.of("partnerId", partnerId, "schemaName", schema));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(((Number) data.get("cardsIssued")).longValue()).isEqualTo(0);
    }

    @Test
    void unsignedStats_is401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.exchange(STATS, HttpMethod.POST,
            new HttpEntity<>("{}", h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void seedCard(String partnerId, String schema, String last4) {
        try {
            PartnerContext.set(new PartnerContext(partnerId, schema, "SANDBOX", "SANDBOX", "INTERNAL", null));
            cardRepository.save(Card.builder()
                .productId(UUID.randomUUID())
                .panEncrypted("411111111111" + last4)
                .panHash(UUID.randomUUID().toString().replace("-", ""))
                .panLast4(last4).bin("41111111").expiryYm("2812")
                .status(CardStatus.ISSUED).virtual(true).build());
        } finally {
            PartnerContext.clear();
        }
    }

    private ResponseEntity<Map> postSigned(String path, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        long ts = Instant.now().getEpochSecond();
        String bodyHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        String signed = "POST|" + path + "|" + ts + "|" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Internal " + sig);
        h.set("X-Internal-Timestamp", String.valueOf(ts));
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }
}
