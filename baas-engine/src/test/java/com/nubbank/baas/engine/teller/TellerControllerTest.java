package com.nubbank.baas.engine.teller;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TellerControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Teller Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("teller@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "teller@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Teller Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createTeller_activate_addCashier_openSession() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Create teller
        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/tellers",
            HttpMethod.POST, new HttpEntity<>(Map.of("name", "Main Teller"), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tellerId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) createResp.getBody().get("data")).get("status")).isEqualTo("INACTIVE");

        // Activate
        ResponseEntity<Map> activateResp = restTemplate.exchange(
            "/baas/v1/tellers/" + tellerId + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) activateResp.getBody().get("data")).get("status")).isEqualTo("ACTIVE");

        // Add cashier
        ResponseEntity<Map> cashierResp = restTemplate.exchange(
            "/baas/v1/tellers/" + tellerId + "/cashiers",
            HttpMethod.POST, new HttpEntity<>(Map.of("isFullDay", true), h), Map.class);
        assertThat(cashierResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String cashierId = ((Map<?, ?>) cashierResp.getBody().get("data")).get("id").toString();

        // Open session
        ResponseEntity<Map> sessionResp = restTemplate.exchange(
            "/baas/v1/tellers/" + tellerId + "/sessions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("cashierId", cashierId, "openingBalance", 50000.0), h), Map.class);
        assertThat(sessionResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) sessionResp.getBody().get("data")).get("status")).isEqualTo("OPEN");
    }

    @Test
    void cashIn_cashOut_settle() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        String tellerId = createActiveTeller(h);
        String cashierId = createCashier(h, tellerId);
        String sessionId = openSession(h, tellerId, cashierId);

        // Cash in 20000
        restTemplate.exchange("/baas/v1/tellers/" + tellerId + "/sessions/" + sessionId + "/transactions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("transactionType", "CASH_IN", "amount", 20000.0), h), Map.class);

        // Cash out 5000
        restTemplate.exchange("/baas/v1/tellers/" + tellerId + "/sessions/" + sessionId + "/transactions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("transactionType", "CASH_OUT", "amount", 5000.0), h), Map.class);

        // Settle: opening=50000 + in=20000 - out=5000 = closing=65000; actualCash=65000 → diff=0
        ResponseEntity<Map> settleResp = restTemplate.exchange(
            "/baas/v1/tellers/" + tellerId + "/sessions/" + sessionId + "/settle",
            HttpMethod.POST, new HttpEntity<>(Map.of("actualCash", 65000.0), h), Map.class);

        assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) settleResp.getBody().get("data");
        assertThat(((Number) data.get("closingBalance")).doubleValue()).isEqualTo(65000.0);
        assertThat(((Number) data.get("difference")).doubleValue()).isEqualTo(0.0);
    }

    private String createActiveTeller(HttpHeaders h) {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/tellers",
            HttpMethod.POST, new HttpEntity<>(Map.of("name", "Test Teller"), h), Map.class);
        String id = ((Map<?, ?>) r.getBody().get("data")).get("id").toString();
        restTemplate.exchange("/baas/v1/tellers/" + id + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        return id;
    }

    private String createCashier(HttpHeaders h, String tellerId) {
        ResponseEntity<Map> r = restTemplate.exchange(
            "/baas/v1/tellers/" + tellerId + "/cashiers",
            HttpMethod.POST, new HttpEntity<>(Map.of("isFullDay", true), h), Map.class);
        return ((Map<?, ?>) r.getBody().get("data")).get("id").toString();
    }

    private String openSession(HttpHeaders h, String tellerId, String cashierId) {
        ResponseEntity<Map> r = restTemplate.exchange(
            "/baas/v1/tellers/" + tellerId + "/sessions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("cashierId", cashierId, "openingBalance", 50000.0), h), Map.class);
        return ((Map<?, ?>) r.getBody().get("data")).get("id").toString();
    }
}
