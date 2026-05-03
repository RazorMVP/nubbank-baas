package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PaymentControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;

    private String jwt;
    private UUID accountA;
    private UUID accountB;
    private PartnerContext testContext;

    @BeforeEach
    void setup() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Payment Test").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schema).contactEmail("pay@test.com").build();
        org = orgRepo.save(org);
        provisioningService.provision(org.getId(), schema);

        testContext = new PartnerContext(org.getId().toString(), schema,
            "SANDBOX", "SANDBOX", "TEST", null);
        PartnerContext.set(testContext);
        Customer cust = customerRepo.save(Customer.builder()
            .firstNameEncrypted("A").lastNameEncrypted("B").build());

        accountA = accountRepo.save(Account.builder().customer(cust)
            .accountNumber("0580000001").balance(new BigDecimal("10000"))
            .availableBalance(new BigDecimal("10000")).currencyCode("NGN")
            .minimumBalance(BigDecimal.ZERO).status(AccountStatus.ACTIVE).build()).getId();

        accountB = accountRepo.save(Account.builder().customer(cust)
            .accountNumber("0580000002").balance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO).currencyCode("NGN")
            .minimumBalance(BigDecimal.ZERO).status(AccountStatus.ACTIVE).build()).getId();
        PartnerContext.clear();

        jwt = jwtService.issue(UUID.randomUUID().toString(), "pay@test.com",
            "PARTNER_TELLER", org.getId().toString(), "Payment Test",
            schema, "SANDBOX", "SANDBOX");
    }

    @Test
    void transfer_debitsSourceCreditsDestination() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "sourceAccountId", accountA.toString(),
            "destinationAccountId", accountB.toString(),
            "amount", 3000.00,
            "description", "Test transfer",
            "idempotencyKey", UUID.randomUUID().toString()
        );

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/payments/transfer", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("COMPLETED");

        // Verify balances — direct repo calls require context (no servlet filter here)
        PartnerContext.set(testContext);
        Account src = accountRepo.findById(accountA).orElseThrow();
        Account dst = accountRepo.findById(accountB).orElseThrow();
        PartnerContext.clear();
        assertThat(src.getBalance()).isEqualByComparingTo("7000");
        assertThat(dst.getBalance()).isEqualByComparingTo("3000");
    }

    @Test
    void transfer_idempotentKey_returnsSamePayment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String idemKey = UUID.randomUUID().toString();

        Map<String, Object> body = Map.of("sourceAccountId", accountA.toString(),
            "destinationAccountId", accountB.toString(), "amount", 500.00,
            "idempotencyKey", idemKey);

        ResponseEntity<Map> r1 = restTemplate.exchange("/baas/v1/payments/transfer",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        ResponseEntity<Map> r2 = restTemplate.exchange("/baas/v1/payments/transfer",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        String id1 = ((Map<?, ?>) r1.getBody().get("data")).get("id").toString();
        String id2 = ((Map<?, ?>) r2.getBody().get("data")).get("id").toString();
        assertThat(id1).isEqualTo(id2); // same payment returned, not duplicated
    }

    @Test
    void transfer_insufficientBalance_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("sourceAccountId", accountA.toString(),
            "destinationAccountId", accountB.toString(), "amount", 99999.00);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/payments/transfer", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
