package com.nubbank.baas.card.bin;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Internal HMAC BIN-lookup endpoint test (FROZEN FEP contract §2).
 *
 * Proves:
 *  - a public-schema {@code card_bin_ranges} row seeded via JdbcTemplate is reachable
 *    by {@code GET /internal/v1/bins/{bin}} on an HMAC call with NO PartnerContext set
 *    (the internal caller is tenant-less; PartnerSchemaProvider falls back to public);
 *  - the range {@code 50600000..50609900} matches normalized BIN {@code 50600012} → 200
 *    with body {@code data: { partnerId, schemaName: "partner_x" }};
 *  - an unmatched BIN → 404 (BIN_NOT_FOUND);
 *  - a request with no HMAC header → 401 (enforced by InternalServiceAuthFilter).
 *
 * The HMAC signer mirrors {@code InternalServiceClient.SigningInterceptor}:
 *   signedString = METHOD|path|epochSeconds|sha256Hex(body)
 *   Authorization: Internal &lt;lowercase-hex HmacSHA256&gt; ; X-Internal-Timestamp: &lt;epochSeconds&gt;
 */
class BinLookupTest extends AbstractCardIntegrationTest {

    // Mirrors app.internal-service.shared-secret in application-test.yml.
    private static final String INTERNAL_SECRET = "test-shared-secret-min-32-chars-long-okay";

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void internalLookup_matchesSeededPublicRow_withNoPartnerContext() {
        UUID partnerId = UUID.randomUUID();
        // Seed the normalized 8-char form so the range actually matches 50600012:
        //   normalize("506000")="50600000", normalize("506099")="50609900",
        //   normalize("50600012")="50600012"  ->  50600000 <= 50600012 <= 50609900
        jdbcTemplate.update("""
            INSERT INTO public.card_bin_ranges
                (bin_start, bin_end, partner_id, schema_name, scheme, active)
            VALUES (?, ?, ?, ?, ?, true)
            """, "50600000", "50609900", partnerId, "partner_x", "MASTERCARD");

        ResponseEntity<Map> ok = hmacGet("/internal/v1/bins/50600012");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ok.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("schemaName")).isEqualTo("partner_x");
        assertThat(data.get("partnerId")).isEqualTo(partnerId.toString());
    }

    @Test
    void internalLookup_noMatch_returns404() {
        ResponseEntity<Map> miss = hmacGet("/internal/v1/bins/99999999");
        assertThat(miss.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) miss.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("BIN_NOT_FOUND");
    }

    @Test
    void internalLookup_withoutHmacHeader_returns401() {
        ResponseEntity<Map> resp = restTemplate.getForEntity("/internal/v1/bins/50600012", Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void sixDigitBin_registeredStartEqualsEnd_coversFullSubRange() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        // Register a 6-digit BIN where start == end.  With the fix, the stored
        // range end is normalizeRangeEnd("507000") = "50700099", so the range
        // [50700000, 50700099] covers any PAN whose first 8 digits are 507000xx.
        // (507000xx chosen to avoid overlap with the 506000xx range in other tests.)
        postBin(partner.jwt, "507000", "507000", "VERVE");

        // PAN "5070001234567890": first-8 = "50700012"  ->  50700000 <= 50700012 <= 50700099
        ResponseEntity<Map> ok = hmacGet("/internal/v1/bins/50700012");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ok.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("schemaName")).isEqualTo(partner.schemaName);
    }

    // ---- HMAC test signer (mirrors InternalServiceClient.SigningInterceptor) ----

    private ResponseEntity<Map> hmacGet(String path) {
        long ts = Instant.now().getEpochSecond();
        byte[] body = new byte[0]; // GET has no body
        String signedString = "GET|" + path + "|" + ts + "|" + sha256Hex(body);
        String sig = hmacHex(signedString);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Internal " + sig);
        headers.set("X-Internal-Timestamp", String.valueOf(ts));

        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(INTERNAL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void postBin(String jwt, String binStart, String binEnd, String scheme) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        restTemplate.exchange("/baas/v1/bins", HttpMethod.POST,
            new HttpEntity<>(Map.of("binStart", binStart, "binEnd", binEnd, "scheme", scheme), headers),
            Map.class);
    }
}
