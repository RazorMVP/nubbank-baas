package com.nubbank.baas.engine.social;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MakerCheckerControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String makerJwt;
    private String checkerJwt;
    private UUID makerUserId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("MC Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("mc@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        // Two distinct users — required for segregation of duties.
        makerUserId = UUID.randomUUID();
        UUID checkerUserId = UUID.randomUUID();
        makerJwt = jwtService.issue(makerUserId.toString(), "maker@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "MC Test",
            schemaName, "SANDBOX", "SANDBOX");
        checkerJwt = jwtService.issue(checkerUserId.toString(), "checker@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "MC Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createAsMaker_then_approveAsChecker() {
        HttpHeaders maker = new HttpHeaders();
        maker.setBearerAuth(makerJwt); maker.setContentType(MediaType.APPLICATION_JSON);

        // Maker creates the request
        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/makercheckers",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "entityType", "LOAN",
                "action", "APPROVE_LOAN",
                "commandAsJson", "{\"loanId\":\"abc-123\",\"amount\":100000}"
            ), maker), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) createResp.getBody().get("data")).get("status")).isEqualTo("PENDING");

        // Checker (different user) approves
        HttpHeaders checker = new HttpHeaders();
        checker.setBearerAuth(checkerJwt);
        ResponseEntity<Map> approveResp = restTemplate.exchange(
            "/baas/v1/makercheckers/" + requestId + "?command=approve",
            HttpMethod.POST, new HttpEntity<>(checker), Map.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) approveResp.getBody().get("data")).get("status")).isEqualTo("APPROVED");
    }

    @Test
    void approveByMaker_isRejected_segregationOfDuties() {
        HttpHeaders maker = new HttpHeaders();
        maker.setBearerAuth(makerJwt); maker.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/makercheckers",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "entityType", "LOAN",
                "action", "APPROVE_LOAN",
                "commandAsJson", "{}"
            ), maker), Map.class);
        String requestId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();

        // Maker tries to approve their own request — must be rejected.
        ResponseEntity<Map> selfApprove = restTemplate.exchange(
            "/baas/v1/makercheckers/" + requestId + "?command=approve",
            HttpMethod.POST, new HttpEntity<>(maker), Map.class);
        assertThat(selfApprove.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
