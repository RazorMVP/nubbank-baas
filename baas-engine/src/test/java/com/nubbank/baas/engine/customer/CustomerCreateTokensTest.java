package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerCreateTokensTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private JdbcTemplate jdbc;

    private String jwt;
    private String schemaName;
    private UUID orgId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Tok").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("tok@partner.com").build());
        orgId = org.getId();
        provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void create_populatesNameSearchTokens() throws Exception {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", "John", "lastName", "Doe"), auth()), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));

        java.sql.Array arr = jdbc.queryForObject(
            "SELECT name_search_tokens FROM " + schemaName + ".customers WHERE id = ?",
            java.sql.Array.class, id);
        Object[] tokens = (Object[]) arr.getArray();
        assertThat(tokens).hasSize(5);   // jo, joh, john, do, doe
    }
}
