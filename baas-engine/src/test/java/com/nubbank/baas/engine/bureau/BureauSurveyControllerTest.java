package com.nubbank.baas.engine.bureau;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BureauSurveyControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Bureau Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("bureau@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "bureau@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Bureau Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createCreditBureau_activate() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/creditbureaus",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "TransUnion Nigeria",
                "implClass", "com.nubbank.baas.ncube.TransUnionAdapter",
                "country", "NGA"), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) createResp.getBody().get("data")).get("active")).isEqualTo(false);

        ResponseEntity<Map> activateResp = restTemplate.exchange(
            "/baas/v1/creditbureaus/" + id + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) activateResp.getBody().get("data")).get("active")).isEqualTo(true);
    }

    @Test
    void createSurveyWithQuestions() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "key", "PPI_NG_2026",
            "name", "Nigeria PPI Survey",
            "countryCode", "NGA",
            "questions", List.of(
                Map.of("question", "Do you own a mobile phone?",
                    "sequenceNo", 1,
                    "responses", List.of(
                        Map.of("response", "Yes", "value", 1, "sequenceNo", 1),
                        Map.of("response", "No", "value", 0, "sequenceNo", 2)
                    ))
            )
        );

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/surveys",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("key")).isEqualTo("PPI_NG_2026");
    }
}
