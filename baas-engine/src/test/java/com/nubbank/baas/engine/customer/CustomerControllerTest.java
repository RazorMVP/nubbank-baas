package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CustomerControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;
    private String schemaName;
    private UUID orgId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Customer Test Partner").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("test@partner.com").build();
        org = orgRepo.save(org);
        orgId = org.getId();
        provisioningService.provision(org.getId(), schemaName);

        jwt = adminJwt(org, schemaName);
    }

    @Test
    void createCustomer_validRequest_returns201WithId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
            "firstName", "John",
            "lastName", "Doe",
            "email", "john.doe@example.com",
            "externalReference", "ext-001"
        );

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsKey("id");
        assertThat(data.get("externalReference")).isEqualTo("ext-001");
        assertThat(data.get("kycStatus")).isEqualTo("PENDING_KYC");
    }

    @Test
    void createCustomer_duplicateExternalRef_returns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("firstName", "Jane", "lastName", "Smith",
            "externalReference", "ext-dupe");

        restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getCustomer_existingId_returns200() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("firstName", "Alice", "lastName", "Wonder");

        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
        String id = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();

        ResponseEntity<Map> getResp = restTemplate.exchange(
            "/baas/v1/customers/" + id, HttpMethod.GET,
            new HttpEntity<>(headers), Map.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) getResp.getBody().get("data")).get("id").toString())
            .isEqualTo(id);
    }

    @Test
    void createCustomer_noToken_returns4xx() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/baas/v1/customers",
            Map.of("firstName", "Jane", "lastName", "Smith"),
            Map.class);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }
}
