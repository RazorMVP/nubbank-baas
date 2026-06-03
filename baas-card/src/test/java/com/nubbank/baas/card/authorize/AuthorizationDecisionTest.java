package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionResponse;
import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.card.PanHasher;
import com.nubbank.baas.card.common.CurrencyMinorUnits;
import com.nubbank.baas.card.config.FieldEncryptor;
import com.nubbank.baas.card.limit.CardLimit;
import com.nubbank.baas.card.limit.CardLimitRepository;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Internal card-authorization-decision stub — FROZEN CROSS-TRACK CONTRACT §2a.
 *
 * <p>The caller is the FEP over HMAC ({@code POST /internal/v1/authorize}). The FEP
 * resolved the tenant via the BIN lookup and passes {@code schemaName + PAN}; the
 * stub resolves the card by deterministic {@code pan_hash} within that tenant schema,
 * applies the ISO 8583 RC mapping, and returns the decision wrapped in the standard
 * {@code ApiResponse} envelope (FEP's HttpCardClient reads {@code .data}).
 *
 * <p>RC mapping (Phase 1C stub): {@code 00} approve · {@code 56} no such card ·
 * {@code 62} blocked/cancelled · {@code 54} not usable (ISSUED/EXPIRED) ·
 * {@code 61} exceeds per-txn limit. Balance is a stub (always sufficient).
 *
 * <p>The PAN is recovered for the request the same way Task 4's determinism test did:
 * the API never returns the PAN, so we read {@code pan_encrypted} straight from the
 * partner's tenant schema and decrypt it with a {@link FieldEncryptor} keyed the same
 * as the running app, then send the cleartext PAN in the authorize body.
 *
 * <p>The HMAC signer mirrors {@code InternalServiceClient.SigningInterceptor} for a
 * POST-with-body: {@code signedString = METHOD|path|epochSeconds|sha256Hex(body)} over
 * the EXACT request-body bytes.
 */
class AuthorizationDecisionTest extends AbstractCardIntegrationTest {

    // Mirrors app.internal-service.shared-secret in application-test.yml.
    private static final String INTERNAL_SECRET = "test-shared-secret-min-32-chars-long-okay";
    private static final String PATH = "/internal/v1/authorize";

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private CardRepository cardRepository;
    @Autowired private CardLimitRepository limitRepository;
    @Autowired private PanHasher panHasher;
    @Autowired private FieldEncryptor fieldEncryptor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CurrencyMinorUnits currencyMinorUnits;
    @Autowired private AuthorizationIdempotencyRepository idempotencyRepository;

    // ---------- happy path ----------

    @Test
    void activeCard_sufficientLimit_approves00() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-approve");
        // perTxn = 25000 major units; amountMinor 5000 → 50.00 major < 25000 → approve.
        setPerTxn(partner, ic.cardId, new BigDecimal("25000"));

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = data(resp);
        assertThat(data.get("decision")).isEqualTo("APPROVE");
        assertThat(data.get("responseCode")).isEqualTo("00");
        // envelope shape — FEP reads .data.{decision,responseCode,message}
        assertThat(data).containsKeys("decision", "responseCode", "message");
    }

    @Test
    void activeCard_noLimitRow_approves00() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-no-limit");
        // No limit row at all → unlimited → approve.

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 999_999, "566"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(data(resp).get("decision")).isEqualTo("APPROVE");
        assertThat(data(resp).get("responseCode")).isEqualTo("00");
    }

    // ---------- declines ----------

    @Test
    void blockedCard_declines62() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-blocked");
        command(partner.jwt, ic.cardId, "block"); // ACTIVE → BLOCKED

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(data(resp).get("decision")).isEqualTo("DECLINE");
        assertThat(data(resp).get("responseCode")).isEqualTo("62");
    }

    @Test
    void cancelledCard_declines62() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-cancelled");
        command(partner.jwt, ic.cardId, "cancel"); // ACTIVE → CANCELLED

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));

        assertThat(data(resp).get("responseCode")).isEqualTo("62");
    }

    @Test
    void issuedNotActivatedCard_declines54() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        // Issue but do NOT activate — stays ISSUED.
        IssuedCard ic = issue(partner, "ext-issued");

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));

        assertThat(data(resp).get("decision")).isEqualTo("DECLINE");
        assertThat(data(resp).get("responseCode")).isEqualTo("54");
    }

    @Test
    void expiredCard_declines54() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-expired");
        // Force the row to EXPIRED directly in the tenant schema (no API command for it).
        jdbcTemplate.update(
            "UPDATE " + partner.schemaName + ".cards SET status = 'EXPIRED' WHERE id = ?",
            ic.cardId);

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));

        assertThat(data(resp).get("responseCode")).isEqualTo("54");
    }

    @Test
    void amountExceedsPerTxn_declines61() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-over-limit");
        // perTxn = 10 major units; amountMinor 5000 → 50.00 major > 10 → decline 61.
        setPerTxn(partner, ic.cardId, new BigDecimal("10"));

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(data(resp).get("decision")).isEqualTo("DECLINE");
        assertThat(data(resp).get("responseCode")).isEqualTo("61");
    }

    @Test
    void noMatchingCard_declines56() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        // A PAN that was never issued → no pan_hash match in the schema.
        String randomPan = "5060001234567890";

        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, randomPan, 5000, "566"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(data(resp).get("decision")).isEqualTo("DECLINE");
        assertThat(data(resp).get("responseCode")).isEqualTo("56");
    }

    // ---------- auth ----------

    @Test
    void withoutHmacHeader_returns401() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.exchange(PATH, HttpMethod.POST,
            new HttpEntity<>(authorizeBody(partner, "5060001234567890", 5000, "566"), headers),
            Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ---------- tenant routing: the set-context targets the right schema ----------

    @Test
    void sameCard_foundInOwnSchema_notFoundInAnotherTenant() {
        TestPartner partnerA = TestPartner.create(orgRepo, provisioningService, jwtService);
        TestPartner partnerB = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partnerA, "ext-a");

        // schemaName = A → card found → APPROVE/00
        ResponseEntity<Map> inA = hmacPost(authorizeBody(partnerA, ic.pan, 5000, "566"));
        assertThat(data(inA).get("responseCode")).isEqualTo("00");

        // Same PAN (→ same pan_hash) but routed to partner B's schema → no such card → 56.
        ResponseEntity<Map> inB = hmacPost(authorizeBody(partnerB, ic.pan, 5000, "566"));
        assertThat(data(inB).get("responseCode")).isEqualTo("56");
    }

    // ---------- the key safety invariant: finally-clear fires on BOTH paths ----------

    @Test
    void decide_clearsPartnerContext_onNormalReturn() {
        // Use a REAL provisioned schema (cards table exists) with NO matching card, so
        // findByPanHash returns empty and decide() returns normally (RC 56) — proving the
        // finally-clear fires on the happy path, not just on exceptions.
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        AuthorizationDecisionService svc =
            new AuthorizationDecisionService(cardRepository, limitRepository, panHasher,
                currencyMinorUnits, idempotencyRepository);
        AuthorizationDecisionRequest req = new AuthorizationDecisionRequest(
            partner.orgId.toString(), partner.schemaName, "5060001234567890", 5000L, "566",
            "000001", "TERM0001", "0101120000");

        assertThat(PartnerContext.get()).isNull();
        AuthorizationDecisionResponse resp = svc.decide(req);
        assertThat(resp.responseCode()).isEqualTo("56"); // normal return (no such card)
        assertThat(PartnerContext.get())
            .as("ThreadLocal must be cleared after a normal decide() return")
            .isNull();
    }

    @Test
    void decide_clearsPartnerContext_evenWhenRepoThrows() {
        CardRepository throwingRepo = Mockito.mock(CardRepository.class);
        Mockito.when(throwingRepo.findByPanHash(Mockito.anyString()))
            .thenThrow(new RuntimeException("boom"));
        AuthorizationDecisionService svc =
            new AuthorizationDecisionService(throwingRepo, limitRepository, panHasher,
                currencyMinorUnits, idempotencyRepository);
        AuthorizationDecisionRequest req = new AuthorizationDecisionRequest(
            UUID.randomUUID().toString(), "partner_x", "5060001234567890", 5000L, "566",
            null, null, null);

        assertThat(PartnerContext.get()).isNull();
        assertThatThrownBy(() -> svc.decide(req)).isInstanceOf(RuntimeException.class);
        assertThat(PartnerContext.get())
            .as("ThreadLocal must be cleared by the finally even when the lookup throws")
            .isNull();
    }

    // ---------- currency scaling + idempotency ----------

    @Test
    void perTxnLimit_threeDecimalCurrency_scaledCorrectly() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-kwd");
        // KWD (414) has 3 minor digits. amountMinor 5000 → 5.000 major. Limit 4 → decline 61.
        setLimit(partner, ic.cardId, new BigDecimal("4"), "414");
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "414"));
        assertThat(data(resp).get("responseCode")).isEqualTo("61");
    }

    @Test
    void unknownCurrency_declines12() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, "5060001234567890", 5000, "999"));
        assertThat(data(resp).get("decision")).isEqualTo("DECLINE");
        assertThat(data(resp).get("responseCode")).isEqualTo("12");
    }

    @Test
    void perTxnLimit_currencyMismatch_declines57() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-ccymismatch");
        setLimit(partner, ic.cardId, new BigDecimal("100000"), "566");
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "840"));
        assertThat(data(resp).get("responseCode")).isEqualTo("57");
    }

    @Test
    void perTxnLimit_currencyMatch_enforced() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-ccymatch");
        setLimit(partner, ic.cardId, new BigDecimal("10"), "566");
        ResponseEntity<Map> resp = hmacPost(authorizeBody(partner, ic.pan, 5000, "566"));
        assertThat(data(resp).get("responseCode")).isEqualTo("61");
    }

    @Test
    void retransmit_sameKey_returnsCachedDecision() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-idem");
        setLimit(partner, ic.cardId, new BigDecimal("25000"), "566");
        Map<String, Object> body = authorizeBody(partner, ic.pan, 5000, "566");

        ResponseEntity<Map> first = hmacPost(body);
        assertThat(data(first).get("responseCode")).isEqualTo("00");

        command(partner.jwt, ic.cardId, "block");
        ResponseEntity<Map> replay = hmacPost(body);
        assertThat(data(replay).get("responseCode"))
            .as("retransmit returns cached decision, not a fresh re-decide")
            .isEqualTo("00");
    }

    @Test
    void differentKey_sameCard_decidesFreshly() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        IssuedCard ic = issueAndActivate(partner, "ext-idem2");
        setLimit(partner, ic.cardId, new BigDecimal("25000"), "566");

        Map<String, Object> first = authorizeBody(partner, ic.pan, 5000, "566");
        assertThat(data(hmacPost(first)).get("responseCode")).isEqualTo("00");

        command(partner.jwt, ic.cardId, "block");
        Map<String, Object> second = authorizeBody(partner, ic.pan, 5000, "566");
        second.put("stan", "000002");   // different STAN → different key → fresh decide → 62
        assertThat(data(hmacPost(second)).get("responseCode")).isEqualTo("62");
    }

    // =================== helpers ===================

    private record IssuedCard(UUID cardId, String pan) {}

    /** Issue a virtual card via the authenticated API, then recover its cleartext PAN. */
    private IssuedCard issue(TestPartner partner, String customerRef) {
        UUID productId = createProduct(partner);
        Map<String, Object> card = data(post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", customerRef, "virtual", true)));
        UUID cardId = UUID.fromString((String) card.get("id"));
        return new IssuedCard(cardId, recoverPan(partner, cardId));
    }

    private IssuedCard issueAndActivate(TestPartner partner, String customerRef) {
        IssuedCard ic = issue(partner, customerRef);
        command(partner.jwt, ic.cardId, "activate");
        return ic;
    }

    /** Read pan_encrypted from the partner's tenant schema and decrypt it (same key as the app). */
    private String recoverPan(TestPartner partner, UUID cardId) {
        String encryptedPan = jdbcTemplate.queryForObject(
            "SELECT pan_encrypted FROM " + partner.schemaName + ".cards WHERE id = ?",
            String.class, cardId);
        return fieldEncryptor.convertToEntityAttribute(encryptedPan);
    }

    /** Set the per-txn limit on a card by writing the limit row within the partner's schema.
     *  Defaults currency to "566" (NGN) to match the existing callers' transaction currency. */
    private void setPerTxn(TestPartner partner, UUID cardId, BigDecimal perTxn) {
        setLimit(partner, cardId, perTxn, "566");
    }

    private void setLimit(TestPartner partner, UUID cardId, BigDecimal perTxn, String currency) {
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "SANDBOX", "SANDBOX", "JWT", "test-user"));
            CardLimit limit = limitRepository.findByCardId(cardId)
                .orElseGet(() -> CardLimit.builder().cardId(cardId).build());
            limit.setPerTxn(perTxn);
            limit.setCurrencyCode(currency);
            limitRepository.save(limit);
        } finally {
            PartnerContext.clear();
        }
    }

    private Map<String, Object> authorizeBody(TestPartner partner, String pan, long amountMinor,
                                              String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner.orgId.toString());
        body.put("schemaName", partner.schemaName);
        body.put("pan", pan);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("stan", "000001");
        body.put("terminalId", "TERM0001");
        body.put("transmissionDateTime", "0101120000");
        return body;
    }

    private UUID createProduct(TestPartner partner) {
        ResponseEntity<Map> created = post(partner.jwt, "/baas/v1/card-products", Map.of(
            "name", "Virtual Debit " + UUID.randomUUID(), "cardType", "DEBIT", "currency", "NGN",
            "binStart", "506000"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString((String) data(created).get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody().get("data");
    }

    private void command(String jwt, UUID id, String cmd) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/cards/" + id + "?command=" + cmd,
            HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
            .as("command " + cmd + " should succeed").isTrue();
    }

    private ResponseEntity<Map> post(String jwt, String path, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
    }

    // ---- HMAC test signer for POST-with-body (mirrors InternalServiceClient.SigningInterceptor) ----

    private ResponseEntity<Map> hmacPost(Map<String, ?> body) {
        byte[] bodyBytes = toJsonBytes(body);
        long ts = Instant.now().getEpochSecond();
        String signedString = "POST|" + PATH + "|" + ts + "|" + sha256Hex(bodyBytes);
        String sig = hmacHex(signedString);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Internal " + sig);
        headers.set("X-Internal-Timestamp", String.valueOf(ts));

        // Send the EXACT bytes we signed (a String entity preserves them verbatim).
        return restTemplate.exchange(PATH, HttpMethod.POST,
            new HttpEntity<>(new String(bodyBytes, StandardCharsets.UTF_8), headers), Map.class);
    }

    private static byte[] toJsonBytes(Map<String, ?> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
}
