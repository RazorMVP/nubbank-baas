package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerKycTransitionTest extends AbstractIntegrationTest {
    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerKycEventRepository eventRepo;
    private String jwt; private String schemaName; private UUID orgId;

    @BeforeEach void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Kyc").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("kyc@partner.com").build());
        orgId = org.getId(); provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "kyc@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Kyc", schemaName, "SANDBOX", "SANDBOX");
    }
    private HttpHeaders auth() { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON); return h; }
    private UUID createCustomer() {
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers", HttpMethod.POST,
            new HttpEntity<>(Map.of("firstName", "A", "lastName", "B"), auth()), Map.class);
        return UUID.fromString((String) ((Map<?, ?>) r.getBody().get("data")).get("id"));
    }

    @Test
    void activate_movesPendingToActive_andRecordsEvent() {
        UUID id = createCustomer();
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/activate",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "docs verified"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?,?>) r.getBody().get("data")).get("kycStatus")).isEqualTo("ACTIVE");

        try {
            PartnerContext.set(new PartnerContext(orgId.toString(), schemaName,
                "SANDBOX", "SANDBOX", "JWT", "x"));
            List<CustomerKycEvent> events = eventRepo.findByCustomerIdOrderByChangedAtDesc(id);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getFromStatus()).isEqualTo("PENDING_KYC");
            assertThat(events.get(0).getToStatus()).isEqualTo("ACTIVE");
            assertThat(events.get(0).getReason()).isEqualTo("docs verified");
            assertThat(events.get(0).getChangedBy()).isNotNull();
            assertThat(events.get(0).getChangedBy()).isNotEqualTo("null");
        } finally {
            PartnerContext.clear();
        }
    }

    @Test
    void suspendFromPending_isIllegal_409() {
        UUID id = createCustomer();
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/suspend",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors = (List<Map<String,Object>>) r.getBody().get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("INVALID_KYC_TRANSITION");
    }

    @Test
    void transition_blankReason_400() {
        UUID id = createCustomer();
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/customers/" + id + "/activate",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", ""), auth()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fullLifecycle_activateSuspendReactivateClose() {
        UUID id = createCustomer();
        post(id, "activate", "ok");
        post(id, "suspend", "risk");
        post(id, "reactivate", "cleared");
        ResponseEntity<Map> closed = post(id, "close", "account closed");
        assertThat(((Map<?,?>) closed.getBody().get("data")).get("kycStatus")).isEqualTo("CLOSED");
        // CLOSED is terminal: any further command is 409
        ResponseEntity<Map> after = restTemplate.exchange("/baas/v1/customers/" + id + "/activate",
            HttpMethod.POST, new HttpEntity<>(Map.of("reason", "no"), auth()), Map.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private ResponseEntity<Map> post(UUID id, String cmd, String reason) {
        return restTemplate.exchange("/baas/v1/customers/" + id + "/" + cmd, HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", reason), auth()), Map.class);
    }
}
