package com.nubbank.baas.card.product;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partner card-product management ({@code /baas/v1/card-products}), authenticated
 * with a TestPartner HMAC JWT.
 *
 * Proves (one assertion per service branch):
 *  - POST creates a product → 201 + a generated {@code id};
 *  - GET lists the partner's own products;
 *  - a second POST with a duplicate name → 409 DUPLICATE_PRODUCT;
 *  - TENANT ISOLATION: partner B never sees partner A's product (the schema IS the
 *    boundary — there is no partnerId column or filter);
 *  - unauthenticated POST → 401 (partner chain);
 *  - validation: missing name → 400; bad currency (length != 3) → 400.
 */
class CardProductTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;

    @Test
    void create_thenList_returnsProduct() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        ResponseEntity<Map> created = post(partner.jwt, Map.of(
            "name", "Virtual Debit", "cardType", "DEBIT", "currency", "NGN"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("id")).isNotNull();
        assertThat(data.get("name")).isEqualTo("Virtual Debit");
        assertThat(data.get("cardType")).isEqualTo("DEBIT");
        assertThat(data.get("currency")).isEqualTo("NGN");
        assertThat(data.get("active")).isEqualTo(Boolean.TRUE);

        ResponseEntity<Map> listed = get(partner.jwt);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) listed.getBody().get("data");
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("name")).isEqualTo("Virtual Debit");
    }

    @Test
    void create_duplicateName_returns409() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        ResponseEntity<Map> first = post(partner.jwt, Map.of(
            "name", "Virtual Debit", "cardType", "DEBIT", "currency", "NGN"));
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> dup = post(partner.jwt, Map.of(
            "name", "Virtual Debit", "cardType", "CREDIT", "currency", "USD"));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dup.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("DUPLICATE_PRODUCT");
    }

    @Test
    void list_isTenantIsolated_partnerBNeverSeesPartnerAsProduct() {
        TestPartner partnerA = TestPartner.create(orgRepo, provisioningService, jwtService);
        TestPartner partnerB = TestPartner.create(orgRepo, provisioningService, jwtService);

        post(partnerA.jwt, Map.of("name", "A Debit", "cardType", "DEBIT", "currency", "NGN"));

        ResponseEntity<Map> listA = get(partnerA.jwt);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataA = (List<Map<String, Object>>) listA.getBody().get("data");
        assertThat(dataA).hasSize(1);
        assertThat(dataA.get(0).get("name")).isEqualTo("A Debit");

        // Partner B's schema is empty — the schema is the isolation boundary.
        ResponseEntity<Map> listB = get(partnerB.jwt);
        assertThat(listB.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataB = (List<Map<String, Object>>) listB.getBody().get("data");
        assertThat(dataB).isEmpty();

        // The same name is therefore free in partner B's schema (no global uniqueness).
        ResponseEntity<Map> createdB = post(partnerB.jwt, Map.of(
            "name", "A Debit", "cardType", "DEBIT", "currency", "USD"));
        assertThat(createdB.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void create_withFullBody_persistsOptionalFields() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "Premium Debit");
        body.put("cardType", "DEBIT");
        body.put("currency", "NGN");
        body.put("binStart", "506000");
        body.put("defaultDailyLimit", 250000.5000);

        ResponseEntity<Map> created = post(partner.jwt, body);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        assertThat(data.get("binStart")).isEqualTo("506000");
        assertThat(((Number) data.get("defaultDailyLimit")).doubleValue()).isEqualTo(250000.5);
    }

    @Test
    void create_withoutAuth_returns401() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            "/baas/v1/card-products",
            Map.of("name", "X", "cardType", "DEBIT", "currency", "NGN"), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void create_missingName_returns400() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ResponseEntity<Map> resp = post(partner.jwt, Map.of("cardType", "DEBIT", "currency", "NGN"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void create_badCurrency_returns400() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ResponseEntity<Map> resp = post(partner.jwt, Map.of(
            "name", "Bad Ccy", "cardType", "DEBIT", "currency", "NAIRA"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("VALIDATION_ERROR");
    }

    // ---- helpers ----

    private ResponseEntity<Map> post(String jwt, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/card-products", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/card-products", HttpMethod.GET,
            new HttpEntity<>(headers), Map.class);
    }
}
