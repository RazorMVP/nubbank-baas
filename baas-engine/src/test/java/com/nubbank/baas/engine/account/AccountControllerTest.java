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
import static org.assertj.core.api.Assertions.*;

class AccountControllerTest extends AbstractIntegrationTest {

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
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Account Test").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("acct@test.com").build();
        org = orgRepo.save(org);
        provisioningService.provision(org.getId(), schemaName);

        // Create a customer in this partner's schema
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        Customer customer = Customer.builder()
            .firstNameEncrypted("Test").lastNameEncrypted("User")
            .build();
        customerId = customerRepo.save(customer).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "acct@test.com",
            "PARTNER_TELLER", org.getId().toString(), "Account Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void openAccount_validCustomer_returns201WithNubanNumber() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "customerId", customerId.toString(),
            "accountTypeLabel", "Savings",
            "currencyCode", "NGN"
        );

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accountNumber").toString()).hasSize(10);
        assertThat(data.get("accountNumber").toString()).matches("[0-9]{10}");
        assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    void depositAndWithdraw_updatesBalance() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Open account
        Map<String, Object> openBody = Map.of("customerId", customerId.toString());
        ResponseEntity<Map> openResp = restTemplate.exchange(
            "/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(openBody, headers), Map.class);
        String accountId = ((Map<?, ?>) openResp.getBody().get("data")).get("id").toString();

        // Deposit 5000
        restTemplate.exchange("/baas/v1/accounts/" + accountId + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 5000.00), headers), Map.class);

        // Withdraw 1000
        restTemplate.exchange("/baas/v1/accounts/" + accountId + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 1000.00), headers), Map.class);

        // Verify balance = 4000
        ResponseEntity<Map> getResp = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId, HttpMethod.GET,
            new HttpEntity<>(headers), Map.class);
        Map<?, ?> data = (Map<?, ?>) getResp.getBody().get("data");
        assertThat(((Number) data.get("balance")).doubleValue()).isEqualTo(4000.0);
    }

    @Test
    void withdraw_insufficientBalance_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> openBody = Map.of("customerId", customerId.toString());
        ResponseEntity<Map> openResp = restTemplate.exchange(
            "/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(openBody, headers), Map.class);
        String accountId = ((Map<?, ?>) openResp.getBody().get("data")).get("id").toString();

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("amount", 9999.00), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
