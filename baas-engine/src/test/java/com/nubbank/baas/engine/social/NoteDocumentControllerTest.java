package com.nubbank.baas.engine.social;

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

class NoteDocumentControllerTest extends AbstractIntegrationTest {

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
            .name("Note Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("note@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "note@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Note Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Chidi").lastNameEncrypted("Aneke").build()).getId();
        PartnerContext.clear();
    }

    @Test
    void addNote_and_document_to_customer() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Add note
        ResponseEntity<Map> noteResp = restTemplate.exchange(
            "/baas/v1/customers/" + customerId + "/notes",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("note", "Customer visited branch for KYC upgrade"), h), Map.class);
        assertThat(noteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Add document
        ResponseEntity<Map> docResp = restTemplate.exchange(
            "/baas/v1/customers/" + customerId + "/documents",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("fileName", "passport.pdf",
                "contentType", "application/pdf", "fileSizeBytes", 204800,
                "storagePath", "/uploads/passport.pdf"), h), Map.class);
        assertThat(docResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // List notes — expects paginated result with 1 entry
        ResponseEntity<Map> listResp = restTemplate.exchange(
            "/baas/v1/customers/" + customerId + "/notes",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat((List<?>) ((Map<?, ?>) listResp.getBody().get("data")).get("content"))
            .hasSize(1);
    }
}
