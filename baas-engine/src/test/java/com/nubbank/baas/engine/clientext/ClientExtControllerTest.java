package com.nubbank.baas.engine.clientext;

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

class ClientExtControllerTest extends AbstractIntegrationTest {

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
            .name("ClientExt Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("cext@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "cext@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "ClientExt Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST"));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Emeka").lastNameEncrypted("Okonkwo").build()).getId();
        PartnerContext.clear();
    }

    @Test
    void addIdentifier_then_address() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        // Add identifier
        ResponseEntity<Map> idResp = restTemplate.exchange(
            "/baas/v1/clients/" + customerId + "/identifiers",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("documentType", "NIN", "documentKey", "12345678901"), h), Map.class);
        assertThat(idResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) idResp.getBody().get("data")).get("documentType")).isEqualTo("NIN");

        // Add address
        ResponseEntity<Map> addrResp = restTemplate.exchange(
            "/baas/v1/clients/" + customerId + "/addresses",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("addressType", "HOME", "street", "12 Banana Island",
                "city", "Lagos", "countryCode", "NGA"), h), Map.class);
        assertThat(addrResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
