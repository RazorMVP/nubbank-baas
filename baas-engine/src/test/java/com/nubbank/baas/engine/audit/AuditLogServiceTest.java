package com.nubbank.baas.engine.audit;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuditLogServiceTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private AuditLogService auditLogService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Audit Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("audit@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "audit@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Audit Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
    }

    @Test
    void log_entry_is_persisted_and_queryable() {
        UUID entityId = UUID.randomUUID();
        auditLogService.log("ACCOUNT", entityId, "DEPOSIT", "test-user",
            null, "{\"amount\":1000}");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/audits?entityType=ACCOUNT&entityId=" + entityId,
            HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) ((Map<?, ?>) resp.getBody().get("data")).get("content");
        assertThat(content).hasSize(1);
        assertThat(((Map<?, ?>) content.get(0)).get("action")).isEqualTo("DEPOSIT");
    }
}
