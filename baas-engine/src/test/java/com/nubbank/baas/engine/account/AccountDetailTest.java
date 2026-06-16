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

class AccountDetailTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;

    private String jwt;
    private String schemaName;
    private UUID orgId;
    private UUID customerId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Acct Detail Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("detail@partner.com").build());
        orgId = org.getId();
        provisioningService.provision(org.getId(), schemaName);

        // The account FK requires a customer — persist one directly in the tenant schema.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace").build()).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "detail@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Acct Detail Test", schemaName, "SANDBOX", "SANDBOX");
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
    void detail_returnsWidenedFields() {
        UUID id = openAccount(Map.of("accountTypeLabel", "Savings", "currencyCode", "NGN"));

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + id, HttpMethod.GET,
            new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");

        assertThat(data.get("customerId")).isEqualTo(customerId.toString());
        assertThat(data.get("customerName")).isEqualTo("Ada Lovelace");
        assertThat(data.get("accountTypeLabel")).isEqualTo("Savings");
        assertThat(data.get("status")).isEqualTo("ACTIVE");
        assertThat(data.get("currencyCode")).isEqualTo("NGN");
        assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) data.get("availableBalance")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) data.get("minimumBalance")).doubleValue()).isEqualTo(0.0);
        assertThat(data.get("allowOverdraft")).isEqualTo(false);
        assertThat(data).containsKey("openedAt");
    }

    @Test
    void detail_unknownId_404() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + UUID.randomUUID(),
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detail_blankLastName_customerNameIsFirstNameOnly() {
        // Persist a customer whose last name is blank (empty string) — schema forbids SQL null
        PartnerContext.set(new PartnerContext(orgId.toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        UUID firstOnlyCustomerId = customerRepo.save(
            Customer.builder().firstNameEncrypted("Ada").lastNameEncrypted("").build()).getId();
        PartnerContext.clear();

        // Open an account for that customer
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("customerId", firstOnlyCustomerId.toString());
        body.put("accountTypeLabel", "Savings");
        body.put("currencyCode", "NGN");
        ResponseEntity<Map> openResp = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, auth()), Map.class);
        UUID accountId = UUID.fromString(
            (String) ((Map<?, ?>) openResp.getBody().get("data")).get("id"));

        // Fetch detail — customerName must be "Ada", never "Ada null" or "null Ada"
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts/" + accountId,
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");

        String customerName = (String) data.get("customerName");
        assertThat(customerName).isEqualTo("Ada");
        assertThat(customerName).doesNotContain("null");
    }
}
