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

class CustomerSearchTest extends AbstractIntegrationTest {
    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    private String jwt; private String schemaName;

    @BeforeEach void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Srch").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("srch@partner.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "srch@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Srch", schemaName, "SANDBOX", "SANDBOX");
    }
    private HttpHeaders auth() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON); return h; }
    private UUID createCustomer(String first, String last, String extRef) {
        Map<String,Object> body = new HashMap<>();
        body.put("firstName", first); body.put("lastName", last);
        if (extRef != null) body.put("externalReference", extRef);
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(body, auth()), Map.class);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
    }

    @Test
    void list_filtersByStatus_andSearchesByNamePrefix_andExternalRef() {
        UUID john = createCustomer("John", "Doe", "ext-100");
        createCustomer("Mary", "Jane", "ext-200");
        restTemplate.exchange("/baas/v1/customers/" + john + "/activate", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason","ok"), auth()), Map.class);

        assertThat(count("?search=joh")).isEqualTo(1);          // name prefix → John
        assertThat(count("?search=ext-200")).isEqualTo(1);      // external ref → Mary
        assertThat(count("?kycStatus=ACTIVE")).isEqualTo(1);    // status → John
        assertThat(count("?kycStatus=PENDING_KYC")).isEqualTo(1); // status → Mary
        assertThat(count("?kycStatus=ACTIVE&search=mary")).isEqualTo(0); // combined → none
        assertThat(count("")).isEqualTo(2);                     // no filter → both
    }

    @SuppressWarnings("unchecked")
    private int count(String query) {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers" + query, HttpMethod.GET,
            new HttpEntity<>(auth()), Map.class);
        Map<String,Object> page = (Map<String,Object>) r.getBody().get("data");
        return ((List<?>) page.get("content")).size();
    }
}
