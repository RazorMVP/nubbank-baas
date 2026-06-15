package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AccountStatusEventsApiTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;

    private String jwt;
    private String schemaName;
    private UUID customerId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Status Events Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("statusevents@partner.com").build());
        provisioningService.provision(org.getId(), schemaName);

        // The account FK requires a customer — persist one directly in the tenant schema.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace").build()).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "statusevents@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Status Events Test", schemaName, "SANDBOX", "SANDBOX");
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** Open an account via the API and return its id. */
    private UUID openAccount(Map<String, Object> body) {
        Map<String, Object> withCustomer = new java.util.HashMap<>(body);
        withCustomer.putIfAbsent("customerId", customerId.toString());
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(withCustomer, auth()), Map.class);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
    }

    @Test
    void statusEvents_returnsHistoryOldestFirst() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "legal hold"), auth()), Map.class);
        restTemplate.exchange("/baas/v1/accounts/" + id + "/unfreeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "released"), auth()), Map.class);

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/status-events",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> events = (List<Map<String,Object>>) r.getBody().get("data");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("fromStatus")).isEqualTo("ACTIVE");
        assertThat(events.get(0).get("toStatus")).isEqualTo("FROZEN");
        assertThat(events.get(0).get("reason")).isEqualTo("legal hold");
        assertThat(events.get(1).get("toStatus")).isEqualTo("ACTIVE");
    }

    @Test
    void statusEvents_unknownAccount_404() {
        ResponseEntity<Map> r = restTemplate.exchange(
            "/baas/v1/accounts/" + UUID.randomUUID() + "/status-events",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
