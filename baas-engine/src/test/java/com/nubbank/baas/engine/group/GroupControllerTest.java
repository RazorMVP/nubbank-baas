package com.nubbank.baas.engine.group;

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

class GroupControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;

    private String jwt;
    private UUID customerId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Group Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("group@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "group@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Group Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ngozi").lastNameEncrypted("Obi").build()).getId();
        PartnerContext.clear();
    }

    @Test
    void createGroup_activate_addMember() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Create group
        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/groups",
            HttpMethod.POST, new HttpEntity<>(Map.of("name", "Ikoyi Women Group"), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String groupId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();

        // Activate
        ResponseEntity<Map> activateResp = restTemplate.exchange(
            "/baas/v1/groups/" + groupId + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) activateResp.getBody().get("data")).get("status")).isEqualTo("ACTIVE");

        // Add member
        ResponseEntity<Map> memberResp = restTemplate.exchange(
            "/baas/v1/groups/" + groupId + "/members/" + customerId,
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(memberResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addDuplicateMember_returns409() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/groups",
            HttpMethod.POST, new HttpEntity<>(Map.of("name", "Test Group"), h), Map.class);
        String groupId = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();
        restTemplate.exchange("/baas/v1/groups/" + groupId + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);

        // First add — succeeds
        restTemplate.exchange("/baas/v1/groups/" + groupId + "/members/" + customerId,
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        // Second add — conflict
        ResponseEntity<Map> dupResp = restTemplate.exchange(
            "/baas/v1/groups/" + groupId + "/members/" + customerId,
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
