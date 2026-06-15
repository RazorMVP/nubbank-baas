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

class AccountListTest extends AbstractIntegrationTest {

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
            .name("Account List Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("accountlist@partner.com").build());
        provisioningService.provision(org.getId(), schemaName);

        // The account FK requires a customer — persist one directly in the tenant schema.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace").build()).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "accountlist@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Account List Test", schemaName, "SANDBOX", "SANDBOX");
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** Open an account, capture its account_number for search assertions. */
    private Map<String,Object> openAccountFull(String typeLabel) {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", customerId.toString(),
                "accountTypeLabel", typeLabel), auth()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> listContent(String query) {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts" + query,
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String,Object> page = (Map<String,Object>) r.getBody().get("data");
        return (List<Map<String,Object>>) page.get("content");
    }

    @Test
    void list_returnsSummaryRows_withCustomerName() {
        openAccountFull("Savings");
        List<Map<String,Object>> content = listContent("");
        assertThat(content).isNotEmpty();
        Map<String,Object> row = content.get(0);
        assertThat(row.get("customerName")).isEqualTo("Ada Lovelace");
        assertThat(row).containsKeys("id", "accountNumber", "customerId",
            "accountTypeLabel", "status", "balance", "currencyCode");
        assertThat(row).doesNotContainKey("availableBalance"); // summary is lean
    }

    @Test
    void list_filtersByStatus() {
        Map<String,Object> a = openAccountFull("Savings");
        Map<String,Object> b = openAccountFull("Current");
        // Freeze account b
        restTemplate.exchange("/baas/v1/accounts/" + b.get("id") + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), auth()), Map.class);

        var activeContent = listContent("?status=ACTIVE");
        assertThat(activeContent).hasSize(1);
        assertThat(activeContent.get(0).get("id")).isEqualTo(a.get("id"));
        assertThat(listContent("?status=FROZEN")).hasSize(1);
        assertThat(listContent("?status=FROZEN").get(0).get("id")).isEqualTo(b.get("id"));
    }

    @Test
    void list_searchesByAccountNumberPrefix() {
        Map<String,Object> a = openAccountFull("Savings");
        openAccountFull("Current");
        String acctNo = a.get("accountNumber").toString();   // 10-digit NUBAN

        // full account number → exactly one match
        assertThat(listContent("?search=" + acctNo)).hasSize(1);
        // a 6-char prefix of it → at least the one account (ILIKE prefix)
        assertThat(listContent("?search=" + acctNo.substring(0, 6))).isNotEmpty();
        // a non-matching string → zero
        assertThat(listContent("?search=ZZZZZZ")).isEmpty();
    }

    @Test
    void list_blankLastName_customerNameIsFirstNameOnly() {
        // Persist a customer with a blank last name — no "null" substring must appear
        PartnerContext.set(new PartnerContext(
            // re-derive orgId from the jwt claim; easier to re-fetch the org
            orgRepo.findBySchemaName(schemaName).get().getId().toString(),
            schemaName, "SANDBOX", "SANDBOX", "TEST", null));
        UUID soloCustomerId = customerRepo.save(
            Customer.builder().firstNameEncrypted("Solo").lastNameEncrypted("").build()).getId();
        PartnerContext.clear();

        // Open an account for that customer via the API
        restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", soloCustomerId.toString(),
                "accountTypeLabel", "Savings"), auth()), Map.class);

        // Fetch the list and find the Solo customer's row
        List<Map<String, Object>> content = listContent("");
        Map<String, Object> soloRow = content.stream()
            .filter(r -> soloCustomerId.toString().equals(r.get("customerId").toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Solo customer's account not found in list"));

        String name = (String) soloRow.get("customerName");
        assertThat(name).isEqualTo("Solo");
        assertThat(name).doesNotContain("null");
    }

    @Test
    void list_paginates() {
        openAccountFull("Savings");
        openAccountFull("Current");
        openAccountFull("Savings");
        assertThat(listContent("?page=0&size=2")).hasSize(2);
        assertThat(listContent("?page=1&size=2")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_invalidStatus_returns400() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts?status=BOGUS",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = (List<Map<String, Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID_STATUS");
    }
}
