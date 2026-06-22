package com.nubbank.baas.engine.partner.key;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerApiKeyControllerTest extends AbstractIntegrationTest {
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;

    @SuppressWarnings("unchecked")
    @Test
    void issuedReadOnlyKey_cannotCreateAccount() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("K").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("k@t.com").build());
        provisioning.provision(org.getId(), schema);
        String adminJwt = adminJwt(org, schema);
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(adminJwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> issued = restTemplate.exchange("/baas/v1/partner-api-keys", HttpMethod.POST,
            new HttpEntity<>(Map.of("name","read-only","scopes", List.of("READ_ACCOUNT")), h), Map.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String rawKey = (String) ((Map<?,?>) issued.getBody().get("data")).get("apiKey");
        assertThat(rawKey).isNotBlank();

        HttpHeaders k = new HttpHeaders(); k.set("Authorization", "ApiKey " + rawKey); k.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("customerId", UUID.randomUUID().toString()), k), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // CREATE_ACCOUNT not in scopes
    }
}
