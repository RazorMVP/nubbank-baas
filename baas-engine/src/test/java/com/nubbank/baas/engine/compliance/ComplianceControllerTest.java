package com.nubbank.baas.engine.compliance;

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

class ComplianceControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private ComplianceService complianceService;

    private String jwt;
    private UUID customerId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Compliance Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("comp@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "comp@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Compliance Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Test").lastNameEncrypted("Customer").build()).getId();
    }

    @Test
    void screenCustomer_returns_clear_stub() {
        SanctionsScreeningResult result = complianceService.screenCustomer(customerId);
        assertThat(result.result()).isEqualTo("CLEAR");
        assertThat(result.provider()).isEqualTo("INTERNAL_STUB");
    }

    @Test
    void listScreeningLog_returns_200() {
        complianceService.screenCustomer(customerId);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/compliance/screening?entityType=CUSTOMER&entityId=" + customerId,
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) ((Map<?, ?>) resp.getBody().get("data")).get("content"))
            .isNotEmpty();
    }
}
