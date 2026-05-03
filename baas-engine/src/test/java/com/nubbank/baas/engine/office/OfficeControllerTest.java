package com.nubbank.baas.engine.office;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OfficeControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Office Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("office@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "office@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Office Test",
            schemaName, "SANDBOX", "SANDBOX");
    }

    @Test
    void createHeadOffice_then_branch_then_staff() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Head office
        ResponseEntity<Map> headResp = restTemplate.exchange("/baas/v1/offices",
            HttpMethod.POST, new HttpEntity<>(Map.of("name", "Head Office"), h), Map.class);
        assertThat(headResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String headId = ((Map<?, ?>) headResp.getBody().get("data")).get("id").toString();

        // Branch under head office
        ResponseEntity<Map> branchResp = restTemplate.exchange("/baas/v1/offices",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Lagos Branch", "parentId", headId), h), Map.class);
        assertThat(branchResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String branchId = ((Map<?, ?>) branchResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) branchResp.getBody().get("data")).get("hierarchy").toString())
            .contains(headId).contains(branchId);

        // Staff in branch
        ResponseEntity<Map> staffResp = restTemplate.exchange("/baas/v1/staff",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", "Tunde", "lastName", "Bello",
                "officeId", branchId, "isLoanOfficer", true), h), Map.class);
        assertThat(staffResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) staffResp.getBody().get("data")).get("isLoanOfficer")).isEqualTo(true);
    }
}
