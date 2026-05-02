package com.nubbank.baas.engine.notification;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NotificationServiceTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private NotificationService notificationService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Notif Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("notif@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "notif@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Notif Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
    }

    @Test
    void logNotification_persists_to_db() throws Exception {
        UUID entityId = UUID.randomUUID();
        notificationService.logNotification("LOAN_APPROVED", "LOAN", entityId,
            NotificationChannel.EMAIL, "customer@example.com",
            "Loan Approved", "{\"loanId\":\"" + entityId + "\"}");

        Thread.sleep(200);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/notifications", HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) ((Map<?, ?>) resp.getBody().get("data")).get("content");
        assertThat(content).isNotEmpty();
    }
}
