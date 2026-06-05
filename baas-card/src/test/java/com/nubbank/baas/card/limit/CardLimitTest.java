package com.nubbank.baas.card.limit;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.engine.EngineClient;
import com.nubbank.baas.card.engine.dto.AccountLookupResult;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Per-card limits integration tests ({@code /baas/v1/cards/{id}/limits} PUT + GET),
 * authenticated with a TestPartner HMAC JWT.
 *
 * <p>Documented behaviour proven here:
 * <ul>
 *   <li>PUT then GET round-trips all four amounts.</li>
 *   <li>PUT has REPLACE semantics: the body fully replaces the limit row — a field
 *       absent (null) from the body becomes null on the row (a second PUT that omits
 *       fields clears them); the SAME row is updated (upsert by card_id), never a
 *       second insert.</li>
 *   <li>GET before any PUT → 200 with an all-null {@code CardLimitResponse}
 *       (limits are optional config; absent = unlimited).</li>
 *   <li>Validation: a negative amount OR {@code perTxn > dailyPurchase} →
 *       400 INVALID_LIMITS (NOT VALIDATION_ERROR).</li>
 *   <li>PUT/GET on a non-existent card → 404 CARD_NOT_FOUND.</li>
 *   <li>Tenant isolation: partner B PUT/GET on partner A's card → 404 CARD_NOT_FOUND
 *       (404 not 403, to avoid enumeration).</li>
 *   <li>Unauthenticated → 401.</li>
 * </ul>
 */
class CardLimitTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private EngineClient engineClient;

    private static final UUID LINKED = UUID.randomUUID();

    @BeforeEach
    void stubEngineLookup() {
        when(engineClient.accountLookup(any())).thenReturn(new AccountLookupResult(true, true, "NGN"));
    }

    @Test
    void put_thenGet_roundTripsAllFourValues() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        ResponseEntity<Map> put = putLimits(partner.jwt, cardId, Map.of(
            "dailyPurchase", 100000, "dailyWithdrawal", 50000,
            "perTxn", 25000, "monthly", 1000000));
        assertThat(put.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> limits = data(put);
        assertLimits(limits, "100000", "50000", "25000", "1000000");
        assertThat(limits.get("cardId")).isEqualTo(cardId);

        // GET reflects the same values.
        Map<String, Object> got = data(getLimits(partner.jwt, cardId));
        assertLimits(got, "100000", "50000", "25000", "1000000");
    }

    @Test
    void secondPut_updatesSameRow_notSecondInsert() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        putLimits(partner.jwt, cardId, Map.of(
            "dailyPurchase", 100000, "dailyWithdrawal", 50000,
            "perTxn", 25000, "monthly", 1000000));
        // Second PUT with different values.
        ResponseEntity<Map> second = putLimits(partner.jwt, cardId, Map.of(
            "dailyPurchase", 200000, "dailyWithdrawal", 60000,
            "perTxn", 30000, "monthly", 2000000));
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertLimits(data(second), "200000", "60000", "30000", "2000000");

        // Exactly one row in the tenant schema for this card.
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + partner.schemaName + ".card_limits WHERE card_id = ?",
            Integer.class, UUID.fromString(cardId));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void put_hasReplaceSemantics_omittedFieldsBecomeNull() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        // Set all four first.
        putLimits(partner.jwt, cardId, Map.of(
            "dailyPurchase", 100000, "dailyWithdrawal", 50000,
            "perTxn", 25000, "monthly", 1000000));

        // Second PUT supplies only dailyPurchase — the rest are absent (REPLACE → null).
        ResponseEntity<Map> partial = putLimits(partner.jwt, cardId,
            singletonNullable("dailyPurchase", 80000));
        assertThat(partial.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> limits = data(partial);
        assertThat(new BigDecimal(limits.get("dailyPurchase").toString())).isEqualByComparingTo("80000");
        assertThat(limits.get("dailyWithdrawal")).isNull();
        assertThat(limits.get("perTxn")).isNull();
        assertThat(limits.get("monthly")).isNull();
    }

    @Test
    void perTxnGreaterThanDailyPurchase_returns400InvalidLimits() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        ResponseEntity<Map> resp = putLimits(partner.jwt, cardId, Map.of(
            "dailyPurchase", 10000, "perTxn", 25000));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(firstErrorCode(resp)).isEqualTo("INVALID_LIMITS");
    }

    @Test
    void negativeAmount_returns400InvalidLimits() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        ResponseEntity<Map> resp = putLimits(partner.jwt, cardId, Map.of(
            "dailyPurchase", -1));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(firstErrorCode(resp)).isEqualTo("INVALID_LIMITS");
    }

    @Test
    void getBeforeAnyPut_returns200WithAllNullAmounts() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        ResponseEntity<Map> resp = getLimits(partner.jwt, cardId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> limits = data(resp);
        assertThat(limits.get("cardId")).isEqualTo(cardId);
        assertThat(limits.get("dailyPurchase")).isNull();
        assertThat(limits.get("dailyWithdrawal")).isNull();
        assertThat(limits.get("perTxn")).isNull();
        assertThat(limits.get("monthly")).isNull();
    }

    @Test
    void put_nonExistentCard_returns404CardNotFound() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ResponseEntity<Map> resp = putLimits(partner.jwt, UUID.randomUUID().toString(),
            Map.of("dailyPurchase", 1000));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(firstErrorCode(resp)).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void get_nonExistentCard_returns404CardNotFound() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ResponseEntity<Map> resp = getLimits(partner.jwt, UUID.randomUUID().toString());
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(firstErrorCode(resp)).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void limits_areTenantIsolated_partnerBSees404() {
        TestPartner partnerA = TestPartner.create(orgRepo, provisioningService, jwtService);
        TestPartner partnerB = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardA = issueCard(partnerA);
        putLimits(partnerA.jwt, cardA, Map.of("dailyPurchase", 100000));

        // Partner B's schema has no such card → 404 CARD_NOT_FOUND (not 403).
        ResponseEntity<Map> put = putLimits(partnerB.jwt, cardA, Map.of("dailyPurchase", 1));
        assertThat(put.getStatusCode().value()).isEqualTo(404);
        assertThat(firstErrorCode(put)).isEqualTo("CARD_NOT_FOUND");

        ResponseEntity<Map> get = getLimits(partnerB.jwt, cardA);
        assertThat(get.getStatusCode().value()).isEqualTo(404);
        assertThat(firstErrorCode(get)).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void put_withoutAuth_returns401() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/cards/" + UUID.randomUUID() + "/limits",
            HttpMethod.PUT, new HttpEntity<>(Map.of("dailyPurchase", 1000)), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void get_withoutAuth_returns401() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/cards/" + UUID.randomUUID() + "/limits",
            HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void updateLimits_persistsAndReturnsCurrencyCode() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        String cardId = issueCard(partner);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("perTxn", 25000);
        body.put("dailyPurchase", 100000);
        body.put("currencyCode", "566");

        ResponseEntity<Map> put = putLimits(partner.jwt, cardId, body);
        assertThat(put.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> data = data(put);
        assertThat(data.get("currencyCode")).isEqualTo("566");

        // GET also reflects the persisted currencyCode.
        Map<String, Object> got = data(getLimits(partner.jwt, cardId));
        assertThat(got.get("currencyCode")).isEqualTo("566");
    }

    // ---- helpers ----

    private void assertLimits(Map<String, Object> limits,
                              String dailyPurchase, String dailyWithdrawal,
                              String perTxn, String monthly) {
        assertThat(new BigDecimal(limits.get("dailyPurchase").toString()))
            .isEqualByComparingTo(dailyPurchase);
        assertThat(new BigDecimal(limits.get("dailyWithdrawal").toString()))
            .isEqualByComparingTo(dailyWithdrawal);
        assertThat(new BigDecimal(limits.get("perTxn").toString()))
            .isEqualByComparingTo(perTxn);
        assertThat(new BigDecimal(limits.get("monthly").toString()))
            .isEqualByComparingTo(monthly);
    }

    /** A single-entry map (Map.of rejects nulls; this allows an explicit value). */
    private Map<String, Object> singletonNullable(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private String issueCard(TestPartner partner) {
        UUID productId = createProduct(partner);
        ResponseEntity<Map> issued = post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", "ext-1", "virtual", true,
            "linkedAccountId", LINKED.toString()));
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        return (String) data(issued).get("id");
    }

    private UUID createProduct(TestPartner partner) {
        ResponseEntity<Map> created = post(partner.jwt, "/baas/v1/card-products", Map.of(
            "name", "Virtual Debit " + UUID.randomUUID(), "cardType", "DEBIT", "currency", "NGN",
            "binStart", "506000"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString((String) data(created).get("id"));
    }

    private ResponseEntity<Map> putLimits(String jwt, String cardId, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/cards/" + cardId + "/limits",
            HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> getLimits(String jwt, String cardId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/cards/" + cardId + "/limits",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> post(String jwt, String path, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private String firstErrorCode(ResponseEntity<Map> resp) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        return (String) errors.get(0).get("code");
    }
}
