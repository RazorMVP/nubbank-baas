package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RoleControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Role Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("role@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);
    }

    @Test
    void createRole_and_assignPermissions() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Create role
        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/roles",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "LOAN_OFFICER", "description", "Manages loans"), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String roleId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();

        // List available permissions (seeded by V2)
        ResponseEntity<Map> permsResp = restTemplate.exchange("/baas/v1/roles/permissions",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(permsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> allPerms = (List<?>) permsResp.getBody().get("data");
        assertThat(allPerms).isNotEmpty();

        // Assign first permission to the role
        String permId = ((Map<?, ?>) allPerms.get(0)).get("id").toString();
        ResponseEntity<Map> assignResp = restTemplate.exchange(
            "/baas/v1/roles/" + roleId + "/permissions",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("permissionIds", List.of(permId)), h), Map.class);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
