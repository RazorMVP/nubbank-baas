package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerUpdateTest extends AbstractIntegrationTest {
    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private com.nubbank.baas.engine.customer.NameTokenizer nameTokenizer;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private String jwt; private String schemaName;

    @BeforeEach void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Upd").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("upd@partner.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "upd@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Upd", schemaName, "SANDBOX", "SANDBOX");
    }
    private HttpHeaders auth() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON); return h; }
    private UUID createCustomer(String first, String last) {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", first, "lastName", last), auth()), Map.class);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
    }

    @Test
    void update_mutatesFields() {
        UUID id = createCustomer("John", "Doe");
        Map<String,Object> body = new HashMap<>();
        body.put("firstName", "Jonathan"); body.put("lastName", "Doe");
        body.put("email", "jon@x.com"); body.put("phone", "0805"); body.put("gender", "M");

        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id, HttpMethod.PUT,
            new HttpEntity<>(body, auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String,Object> data = (Map<String,Object>) r.getBody().get("data");
        assertThat(data.get("firstName")).isEqualTo("Jonathan");
        assertThat(data.get("email")).isEqualTo("jon@x.com");
        assertThat(data.get("gender")).isEqualTo("M");
    }

    @Test
    void update_retokenizesName_soSearchFindsNewName() {
        UUID id = createCustomer("John", "Doe");
        Map<String,Object> body = new HashMap<>();
        body.put("firstName", "Jonathan"); body.put("lastName", "Doe");
        restTemplate.exchange("/baas/v1/customers/" + id, HttpMethod.PUT,
            new HttpEntity<>(body, auth()), Map.class);

        java.sql.Array arr = jdbc.queryForObject(
            "SELECT name_search_tokens FROM " + schemaName + ".customers WHERE id = ?",
            java.sql.Array.class, id);
        java.util.Set<String> tokens;
        try {
            Object raw = arr.getArray();
            if (raw instanceof String[]) {
                tokens = new java.util.HashSet<>(java.util.Arrays.asList((String[]) raw));
            } else {
                Object[] objs = (Object[]) raw;
                tokens = new java.util.HashSet<>();
                for (Object o : objs) tokens.add(String.valueOf(o));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // After renaming John -> Jonathan, the index reflects the new name:
        assertThat(tokens).contains(nameTokenizer.queryToken("jonathan"));
        // and the old full-word token "john" is gone ("john" is NOT a prefix of "jonathan").
        assertThat(tokens).doesNotContain(nameTokenizer.queryToken("john"));
    }

    @Test
    void update_unknownId_404() {
        Map<String,Object> body = new HashMap<>();
        body.put("firstName", "X"); body.put("lastName", "Y");
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + UUID.randomUUID(),
            HttpMethod.PUT, new HttpEntity<>(body, auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_blankFirstName_400() {
        UUID id = createCustomer("John", "Doe");
        Map<String,Object> body = new HashMap<>();
        body.put("firstName", ""); body.put("lastName", "Doe");
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id, HttpMethod.PUT,
            new HttpEntity<>(body, auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
