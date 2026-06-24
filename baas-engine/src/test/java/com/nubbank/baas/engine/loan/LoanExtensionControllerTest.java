package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.*;
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

class LoanExtensionControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;
    @Autowired private LoanProductRepository loanProductRepo;
    @Autowired private LoanRepository loanRepo;

    private String jwt;
    private UUID loanId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Ext Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("ext@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName, "SANDBOX", "SANDBOX", "TEST", null));
        var customer = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Test").lastNameEncrypted("User").build());
        var account = accountRepo.save(Account.builder().customer(customer)
            .accountNumber("0580000099").balance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO).currencyCode("NGN").minimumBalance(BigDecimal.ZERO).build());
        var product = loanProductRepo.save(LoanProduct.builder()
            .name("Test Product").shortName("TP01")
            .minPrincipal(new BigDecimal("10000")).maxPrincipal(new BigDecimal("500000"))
            .defaultPrincipal(new BigDecimal("50000")).nominalInterestRate(new BigDecimal("18"))
            .repaymentType(RepaymentType.ANNUITY).numberOfRepayments(6)
            .repaymentEvery(1).repaymentFrequency("MONTHS").build());
        var loan = loanRepo.save(Loan.builder()
            .customer(customer).loanProduct(product)
            .loanAccountNumber("LN-EXT-0001")
            .principalAmount(new BigDecimal("50000"))
            .outstandingBalance(BigDecimal.ZERO)
            .interestRate(new BigDecimal("18"))
            .numberOfRepayments(6).repaymentEvery(1).repaymentFrequency("MONTHS")
            .linkedAccount(account).currencyCode("NGN").build());
        loanId = loan.getId();
        PartnerContext.clear();
    }

    @Test
    void addGuarantor_external_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/loans/" + loanId + "/guarantors", HttpMethod.POST,
            new HttpEntity<>(Map.of("guarantorType", "EXTERNAL",
                "firstName", "Ade", "lastName", "Bode",
                "email", "ade@example.com", "phone", "+2348012345678"), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("guarantorType")).isEqualTo("EXTERNAL");
    }

    @Test
    void addCollateral_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/loans/" + loanId + "/collaterals", HttpMethod.POST,
            new HttpEntity<>(Map.of("description", "2019 Toyota Camry",
                "value", 3500000, "currencyCode", "NGN"), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void createRescheduleRequest_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/loans/" + loanId + "/reschedule", HttpMethod.POST,
            new HttpEntity<>(Map.of("graceOnPrincipal", 1, "graceOnInterest", 0,
                "extraTerms", 3, "reason", "Financial hardship"), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("status")).isEqualTo("PENDING");
    }
}
