package com.nubbank.baas.engine.system;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SystemConfigControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Config Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("config@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "config@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Config Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void getSeededConfigurations_returns_ok() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/configurations",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> configs = (List<?>) ((Map<?, ?>) resp.getBody().get("data")).get("content");
        assertThat(configs).isNotEmpty();
    }

    @Test
    void createCodeAndCodeValues() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Create code
        ResponseEntity<Map> codeResp = restTemplate.exchange("/baas/v1/codes",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "NATIONALITY"), h), Map.class);
        assertThat(codeResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String codeId = ((Map<?, ?>) codeResp.getBody().get("data")).get("id").toString();

        // Add code value
        ResponseEntity<Map> valueResp = restTemplate.exchange(
            "/baas/v1/codes/" + codeId + "/codevalues",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("value", "Nigerian", "position", 1), h), Map.class);
        assertThat(valueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listSeededPaymentTypes() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/paymenttypes",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> types = (List<?>) ((Map<?, ?>) resp.getBody().get("data")).get("content");
        assertThat(types).isNotEmpty(); // seeded by V2: Cash, Cheque, Direct Transfer, etc.
    }
}
