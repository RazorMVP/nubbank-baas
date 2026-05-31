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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partner BIN registration + listing ({@code /baas/v1/bins}), authenticated with a
 * TestPartner HMAC JWT.
 *
 * Proves:
 *  - POST registers a range and returns 201 + BinRangeResponse;
 *  - GET lists only the current partner's ranges (cross-tenant isolation: partner B
 *    never sees partner A's range);
 *  - the persisted row's partner_id / schema_name come from the JWT context, NOT
 *    from any body field (the body has no such fields);
 *  - bin_start > bin_end → 400 INVALID_BIN_RANGE.
 */
class BinRegistrationTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void register_persistsRange_withContextDerivedTenant() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        ResponseEntity<Map> created = post(partner.jwt, Map.of(
            "binStart", "412345", "binEnd", "412399", "scheme", "VISA"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) created.getBody().get("data");
        assertThat(data).isNotNull();
        // Stored in normalized 8-char form.
        assertThat(data.get("binStart")).isEqualTo("41234500");
        assertThat(data.get("binEnd")).isEqualTo("41239900");
        assertThat(data.get("scheme")).isEqualTo("VISA");
        assertThat(data.get("active")).isEqualTo(Boolean.TRUE);

        // partner_id / schema_name MUST come from the authenticated context (JWT),
        // never the body. Verify the persisted row directly.
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT partner_id, schema_name FROM public.card_bin_ranges WHERE id = ?::uuid",
            data.get("id").toString());
        assertThat(row.get("partner_id").toString()).isEqualTo(partner.orgId.toString());
        assertThat(row.get("schema_name")).isEqualTo(partner.schemaName);
    }

    @Test
    void list_returnsOnlyCurrentPartnersRanges() {
        TestPartner partnerA = TestPartner.create(orgRepo, provisioningService, jwtService);
        TestPartner partnerB = TestPartner.create(orgRepo, provisioningService, jwtService);

        post(partnerA.jwt, Map.of("binStart", "510000", "binEnd", "510099", "scheme", "MASTERCARD"));

        // Partner A sees its own range.
        ResponseEntity<Map> listA = get(partnerA.jwt);
        assertThat(listA.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataA = (List<Map<String, Object>>) listA.getBody().get("data");
        assertThat(dataA).hasSize(1);
        assertThat(dataA.get(0).get("binStart")).isEqualTo("51000000");

        // Partner B sees nothing — cross-tenant isolation.
        ResponseEntity<Map> listB = get(partnerB.jwt);
        assertThat(listB.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataB = (List<Map<String, Object>>) listB.getBody().get("data");
        assertThat(dataB).isEmpty();
    }

    @Test
    void register_invertedRange_returns400() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        ResponseEntity<Map> resp = post(partner.jwt, Map.of(
            "binStart", "510099", "binEnd", "510000", "scheme", "MASTERCARD"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID_BIN_RANGE");
    }

    @Test
    void register_withoutAuth_returns401() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            "/baas/v1/bins", Map.of("binStart", "510000", "binEnd", "510099"), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ---- helpers ----

    private ResponseEntity<Map> post(String jwt, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/bins", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/bins", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
