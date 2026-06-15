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

class AccountTransitionTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountStatusEventRepository statusEventRepo;

    private String jwt;
    private String schemaName;
    private UUID orgId;
    private UUID customerId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Acct Transition Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("transition@partner.com").build());
        orgId = org.getId();
        provisioningService.provision(org.getId(), schemaName);

        // The account FK requires a customer — persist one directly in the tenant schema.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace").build()).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "transition@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Acct Transition Test", schemaName, "SANDBOX", "SANDBOX");
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
    void freeze_movesActiveToFrozen() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "legal hold"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?,?>) r.getBody().get("data")).get("status")).isEqualTo("FROZEN");
    }

    @Test
    void unfreeze_movesFrozenToActive() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/unfreeze",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "released"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?,?>) r.getBody().get("data")).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void close_movesActiveToClosed_whenBalanceZero() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/close",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "customer request"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?,?>) r.getBody().get("data")).get("status")).isEqualTo("CLOSED");

        // a status-event row must be written for the close
        List<AccountStatusEvent> events = statusEventRepoInTenant(id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFromStatus()).isEqualTo("ACTIVE");
        assertThat(events.get(0).getToStatus()).isEqualTo("CLOSED");
        assertThat(events.get(0).getReason()).isEqualTo("customer request");
    }

    @Test
    void unfreeze_fromActive_isIllegal_400() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/unfreeze",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID_ACCOUNT_TRANSITION");
    }

    @Test
    void close_fromFrozen_isIllegal_400() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/close",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID_ACCOUNT_TRANSITION");
    }

    @Test
    void close_withNonZeroBalance_409() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit", HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 100.00), auth()), Map.class);
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/close",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_BALANCE_NONZERO");
    }

    @Test
    void transition_blankReason_400() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", ""), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Read status events directly from the tenant schema (repo is schema-routed via PartnerContext). */
    private List<AccountStatusEvent> statusEventRepoInTenant(UUID accountId) {
        PartnerContext.set(new PartnerContext(orgId.toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        try {
            return statusEventRepo.findByAccountIdOrderByChangedAtAsc(accountId);
        } finally {
            PartnerContext.clear();
        }
    }
}
