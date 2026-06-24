package com.nubbank.baas.engine.deposit;

import com.nubbank.baas.engine.AbstractIntegrationTest;
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

class FixedDepositControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private DepositProductRepository depositProductRepo;

    private String jwt;
    private UUID customerId;
    private UUID productId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("FD Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("fd@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName, "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Jane").lastNameEncrypted("Doe").build()).getId();
        productId = depositProductRepo.save(DepositProduct.builder()
            .name("Fixed Rate Product").shortName("FX01")
            .accountType(AccountType.SAVINGS).minimumBalance(BigDecimal.ZERO)
            .nominalInterestRate(new BigDecimal("8.5")).allowOverdraft(false).build()).getId();
        PartnerContext.clear();
    }

    @Test
    void createFixedDeposit_validRequest_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("customerId", customerId.toString(),
            "productId", productId.toString(), "depositAmount", 200000,
            "depositTerm", 12, "depositTermUnit", "MONTHS", "currencyCode", "NGN");

        ResponseEntity<Map> response = restTemplate.exchange("/baas/v1/fixed-deposits",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("SUBMITTED");
        assertThat(data.get("accountNumber").toString()).hasSize(10);
    }

    @Test
    void approveFixedDeposit_changes_status_to_approved() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("customerId", customerId.toString(),
            "productId", productId.toString(), "depositAmount", 100000,
            "depositTerm", 6, "depositTermUnit", "MONTHS", "currencyCode", "NGN");
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/fixed-deposits",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        String id = ((Map<?, ?>) create.getBody().get("data")).get("id").toString();

        ResponseEntity<Map> approve = restTemplate.exchange(
            "/baas/v1/fixed-deposits/" + id + "?command=approve",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);

        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) approve.getBody().get("data")).get("status")).isEqualTo("APPROVED");
    }
}
