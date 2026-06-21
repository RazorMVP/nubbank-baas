package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AccountMoneyGatingTest extends AbstractIntegrationTest {

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
            .name("Acct Money Gating Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("gating@partner.com").build());
        provisioningService.provision(org.getId(), schemaName);

        // The account FK requires a customer — persist one directly in the tenant schema.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace").build()).getId();
        PartnerContext.clear();

        jwt = adminJwt(org, schemaName);
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
    void deposit_onFrozenAccount_succeeds() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit",
            HttpMethod.POST, new HttpEntity<>(Map.of("amount", 500.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) ((Map<?,?>) r.getBody().get("data")).get("runningBalance"))
            .doubleValue()).isEqualTo(500.0);
    }

    @Test
    void deposit_onClosedAccount_409() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/close", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "closed"), auth()), Map.class);

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit",
            HttpMethod.POST, new HttpEntity<>(Map.of("amount", 500.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_NOT_ACCEPTING_CREDITS");
    }

    @Test
    void withdraw_onFrozenAccount_409() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/deposit", HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 1000.00), auth()), Map.class);
        restTemplate.exchange("/baas/v1/accounts/" + id + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/withdraw",
            HttpMethod.POST, new HttpEntity<>(Map.of("amount", 100.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_NOT_ACCEPTING_DEBITS");
    }

    @Test
    void withdraw_onClosedAccount_409() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        restTemplate.exchange("/baas/v1/accounts/" + id + "/close", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "customer request"), auth()), Map.class);

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/withdraw",
            HttpMethod.POST, new HttpEntity<>(Map.of("amount", 50.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("ACCOUNT_NOT_ACCEPTING_DEBITS");
    }

    @Test
    void withdraw_onActiveAccount_stillEnforcesBalanceFloor_400() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings"));
        // ACTIVE, zero balance — debit beyond the floor is INSUFFICIENT_BALANCE (the gate passes first).
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id + "/withdraw",
            HttpMethod.POST, new HttpEntity<>(Map.of("amount", 50.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }
}
