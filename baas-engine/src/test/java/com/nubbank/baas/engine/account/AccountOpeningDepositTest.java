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

class AccountOpeningDepositTest extends AbstractIntegrationTest {

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
            .name("Acct Opening Deposit Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("opening@partner.com").build());
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

    @Test
    void open_withOpeningDeposit_setsBalanceAndWritesCreditTransaction() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", customerId.toString(),
                "accountTypeLabel", "Savings", "openingDeposit", 2500.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
        assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(2500.0);
        assertThat(((Number) data.get("availableBalance")).doubleValue()).isEqualTo(2500.0);
        // detail shape now returned by open
        assertThat(data.get("customerName")).isEqualTo("Ada Lovelace");

        String accountId = data.get("id").toString();
        ResponseEntity<Map> txns = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId + "/transactions", HttpMethod.GET,
            new HttpEntity<>(auth()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String,Object> page = (Map<String,Object>) txns.getBody().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> content = (List<Map<String,Object>>) page.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("transactionType")).isEqualTo("CREDIT");
        assertThat(content.get(0).get("reference")).isEqualTo("OPENING_DEPOSIT");
        assertThat(((Number) content.get(0).get("amount")).doubleValue()).isEqualTo(2500.0);
    }

    @Test
    void open_withoutOpeningDeposit_isZeroBalance_noTransaction() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", customerId.toString(),
                "accountTypeLabel", "Savings"), auth()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
        assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(0.0);

        String accountId = data.get("id").toString();
        ResponseEntity<Map> txns = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId + "/transactions", HttpMethod.GET,
            new HttpEntity<>(auth()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String,Object> page = (Map<String,Object>) txns.getBody().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> content = (List<Map<String,Object>>) page.get("content");
        assertThat(content).isEmpty();
    }

    @Test
    void open_withNegativeOpeningDeposit_400() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", customerId.toString(),
                "accountTypeLabel", "Savings", "openingDeposit", -1.00), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
