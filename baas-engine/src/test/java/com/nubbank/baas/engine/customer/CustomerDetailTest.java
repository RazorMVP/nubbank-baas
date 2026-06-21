package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerDetailTest extends AbstractIntegrationTest {
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    private String jwt; private String schemaName; private UUID orgId;

    @BeforeEach void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Det").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("det@partner.com").build());
        orgId = org.getId(); provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);
    }
    private HttpHeaders auth() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON); return h; }
    private UUID createCustomer(Map<String, Object> body) {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, auth()), Map.class);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
    }

    @Test
    void detail_returnsMaskedIdentityAndExtraFields() {
        UUID id = createCustomer(Map.of(
            "firstName", "Ada", "lastName", "Lovelace", "phone", "08030001234",
            "dateOfBirth", "1990-01-01", "gender", "F",
            "bvn", "22233344455", "nin", "99988877766"));

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id, HttpMethod.GET,
            new HttpEntity<>(auth()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");

        assertThat(data.get("firstName")).isEqualTo("Ada");
        assertThat(data.get("phone")).isEqualTo("08030001234");
        assertThat(data.get("gender")).isEqualTo("F");
        assertThat(data.get("bvnMasked")).isEqualTo("•••••••4455");
        assertThat(data.get("ninMasked")).isEqualTo("•••••••7766");
        assertThat(data).doesNotContainKey("bvn");   // full value never returned
    }

    @Test
    void create_malformedDateOfBirth_returns400() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", "Bad", "lastName", "Date",
                "dateOfBirth", "01/01/1990"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getById_unknownId_returns404() {
        ResponseEntity<Map> r = restTemplate.exchange(
            "/baas/v1/customers/" + java.util.UUID.randomUUID(),
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
