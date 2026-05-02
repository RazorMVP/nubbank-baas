package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.product.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LoanControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;
    @Autowired private LoanProductRepository loanProductRepo;

    private String jwt;
    private UUID customerId;
    private UUID accountId;
    private UUID loanProductId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Loan Test " + UUID.randomUUID())
            .status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("loan@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "loan@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Loan Test",
            schemaName, "SANDBOX", "SANDBOX");

        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName, "SANDBOX", "SANDBOX", "TEST", null));
        var customer = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ali").lastNameEncrypted("Baba").build());
        customerId = customer.getId();
        var account = accountRepo.save(Account.builder()
            .customer(customer).accountNumber("ACC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
            .balance(BigDecimal.ZERO).availableBalance(BigDecimal.ZERO)
            .currencyCode("NGN").minimumBalance(BigDecimal.ZERO).build());
        accountId = account.getId();
        loanProductId = loanProductRepo.save(LoanProduct.builder()
            .name("Business Loan").shortName("BL" + UUID.randomUUID().toString().replace("-", "").substring(0, 4))
            .minPrincipal(new BigDecimal("50000")).maxPrincipal(new BigDecimal("1000000"))
            .defaultPrincipal(new BigDecimal("200000"))
            .nominalInterestRate(new BigDecimal("24"))
            .repaymentType(RepaymentType.ANNUITY)
            .numberOfRepayments(12).repaymentEvery(1).repaymentFrequency("MONTHS")
            .build()).getId();
        PartnerContext.clear();
    }

    @Test
    void applyAndApproveAndDisburse_fullFlow() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Apply
        Map<String, Object> applyBody = Map.of(
            "customerId", customerId.toString(),
            "loanProductId", loanProductId.toString(),
            "principalAmount", 100000,
            "numberOfRepayments", 6,
            "linkedAccountId", accountId.toString()
        );
        ResponseEntity<Map> applyResp = restTemplate.exchange("/baas/v1/loans",
            HttpMethod.POST, new HttpEntity<>(applyBody, h), Map.class);
        assertThat(applyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String loanId = ((Map<?, ?>) applyResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) applyResp.getBody().get("data")).get("status")).isEqualTo("SUBMITTED");

        // Approve
        ResponseEntity<Map> approveResp = restTemplate.exchange(
            "/baas/v1/loans/" + loanId + "?command=approve",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) approveResp.getBody().get("data")).get("status")).isEqualTo("APPROVED");

        // Disburse
        ResponseEntity<Map> disburseResp = restTemplate.exchange(
            "/baas/v1/loans/" + loanId + "?command=disburse",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(disburseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) disburseResp.getBody().get("data")).get("status")).isEqualTo("ACTIVE");

        // Schedule
        ResponseEntity<Map> scheduleResp = restTemplate.exchange(
            "/baas/v1/loans/" + loanId + "/schedule",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(scheduleResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> schedule = (List<?>) ((Map<?, ?>) scheduleResp.getBody().get("data")).get("content");
        assertThat(schedule).hasSize(6);
    }

    @Test
    void repay_reducesOutstandingBalance() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> applyBody = Map.of("customerId", customerId.toString(),
            "loanProductId", loanProductId.toString(), "principalAmount", 60000,
            "numberOfRepayments", 3, "linkedAccountId", accountId.toString());
        ResponseEntity<Map> applyResp = restTemplate.exchange("/baas/v1/loans",
            HttpMethod.POST, new HttpEntity<>(applyBody, h), Map.class);
        String loanId = ((Map<?, ?>) applyResp.getBody().get("data")).get("id").toString();

        restTemplate.exchange("/baas/v1/loans/" + loanId + "?command=approve",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        restTemplate.exchange("/baas/v1/loans/" + loanId + "?command=disburse",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);

        restTemplate.exchange("/baas/v1/loans/" + loanId + "/repayments",
            HttpMethod.POST, new HttpEntity<>(Map.of("amount", 22000.00), h), Map.class);

        ResponseEntity<Map> getResp = restTemplate.exchange("/baas/v1/loans/" + loanId,
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        double outstanding = ((Number) ((Map<?, ?>) getResp.getBody().get("data")).get("outstandingBalance")).doubleValue();
        assertThat(outstanding).isLessThan(60000.0);
    }
}
