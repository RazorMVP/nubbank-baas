package com.nubbank.baas.engine.charge;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ChargeControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Charge Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("charge@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "charge@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Charge Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createCharge_flat_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange("/baas/v1/charges",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Processing Fee",
                "chargeType", "LOAN_DISBURSEMENT",
                "calculationType", "FLAT",
                "amount", 2500.00, "currencyCode", "NGN"), h), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("name")).isEqualTo("Processing Fee");
        assertThat(data.get("calculationType")).isEqualTo("FLAT");
    }

    @Test
    void createCharge_percentOfAmount_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange("/baas/v1/charges",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Management Fee",
                "chargeType", "LOAN_DISBURSEMENT",
                "calculationType", "PERCENT_OF_AMOUNT",
                "amount", 1.5), h), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
