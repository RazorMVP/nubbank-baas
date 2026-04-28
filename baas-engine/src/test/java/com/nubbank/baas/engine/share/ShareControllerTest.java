package com.nubbank.baas.engine.share;

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

class ShareControllerTest extends AbstractIntegrationTest {

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
            .name("Share Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("share@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "share@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Share Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName, "SANDBOX", "SANDBOX", "TEST"));
        customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("John").lastNameEncrypted("Coop").build()).getId();
        PartnerContext.clear();
    }

    @Test
    void createShareProduct_and_purchaseShares() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);

        // Create product
        ResponseEntity<Map> productResp = restTemplate.exchange("/baas/v1/share-products",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Coop Shares", "shortName", "CS01",
                "totalShares", 1000000, "unitPrice", 100.0, "minimumShares", 10), h), Map.class);
        assertThat(productResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productId = ((Map<?, ?>) productResp.getBody().get("data")).get("id").toString();

        // Open share account
        ResponseEntity<Map> accountResp = restTemplate.exchange("/baas/v1/share-accounts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", customerId.toString(), "productId", productId), h), Map.class);
        assertThat(accountResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = ((Map<?, ?>) accountResp.getBody().get("data")).get("id").toString();

        // Approve + activate
        restTemplate.exchange("/baas/v1/share-accounts/" + accountId + "?command=approve",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        restTemplate.exchange("/baas/v1/share-accounts/" + accountId + "?command=activate",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);

        // Purchase 50 shares
        ResponseEntity<Map> txResp = restTemplate.exchange(
            "/baas/v1/share-accounts/" + accountId + "/transactions?type=purchase",
            HttpMethod.POST, new HttpEntity<>(Map.of("numberOfShares", 50), h), Map.class);

        assertThat(txResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> txData = (Map<?, ?>) txResp.getBody().get("data");
        assertThat(((Number) txData.get("numberOfShares")).intValue()).isEqualTo(50);
        assertThat(((Number) txData.get("totalAmount")).doubleValue()).isEqualTo(5000.0);
    }
}
