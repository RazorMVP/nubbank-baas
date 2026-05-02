package com.nubbank.baas.engine.rate;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FloatingRateControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Rate Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("rate@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "rate@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Rate Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createFloatingRate_withPeriods() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "name", "CBN Base Rate",
            "isBaseLendingRate", true,
            "periods", List.of(
                Map.of("fromDate", "2026-01-01", "interestRate", 18.5,
                    "isDifferentialToBaseLending", false),
                Map.of("fromDate", "2026-07-01", "interestRate", 20.0,
                    "isDifferentialToBaseLending", false)
            )
        );

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/floatingrates",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("name")).isEqualTo("CBN Base Rate");
        assertThat((List<?>) data.get("periods")).hasSize(2);
    }
}
