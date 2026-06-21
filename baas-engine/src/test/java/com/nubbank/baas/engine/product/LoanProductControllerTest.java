package com.nubbank.baas.engine.product;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LoanProductControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Product Test").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("prod@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);
    }

    @Test
    void createLoanProduct_validRequest_returns201() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt); headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("name", "Personal Loan", "shortName", "PL01",
            "minPrincipal", 50000, "maxPrincipal", 500000, "defaultPrincipal", 100000,
            "nominalInterestRate", 24.0, "repaymentType", "ANNUITY",
            "numberOfRepayments", 12, "repaymentEvery", 1, "repaymentFrequency", "MONTHS");

        ResponseEntity<Map> response = restTemplate.exchange("/baas/v1/loan-products",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("name")).isEqualTo("Personal Loan");
        assertThat(data.get("shortName")).isEqualTo("PL01");
        assertThat(data.get("active")).isEqualTo(true);
    }

    @Test
    void listLoanProducts_returnsCreatedProducts() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt); headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("name", "SME Loan", "shortName", "SM01",
            "minPrincipal", 100000, "maxPrincipal", 5000000, "defaultPrincipal", 500000,
            "nominalInterestRate", 18.0, "repaymentType", "ANNUITY",
            "numberOfRepayments", 24, "repaymentEvery", 1, "repaymentFrequency", "MONTHS");
        restTemplate.exchange("/baas/v1/loan-products", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        ResponseEntity<Map> list = restTemplate.exchange("/baas/v1/loan-products",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) ((Map<?, ?>) list.getBody().get("data")).get("content");
        assertThat(content).isNotEmpty();
    }

    @Test
    void createLoanProduct_duplicateShortName_returns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt); headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("name", "Loan A", "shortName", "LA01",
            "minPrincipal", 10000, "maxPrincipal", 100000, "defaultPrincipal", 50000,
            "nominalInterestRate", 20.0, "repaymentType", "ANNUITY",
            "numberOfRepayments", 6, "repaymentEvery", 1, "repaymentFrequency", "MONTHS");

        restTemplate.exchange("/baas/v1/loan-products", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        ResponseEntity<Map> second = restTemplate.exchange("/baas/v1/loan-products",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
