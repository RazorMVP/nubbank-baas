package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RoleScopingTest extends AbstractIntegrationTest {
    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;

    @SuppressWarnings("unchecked")
    @Test
    void list_isPartnerScoped_and_builtInDelete_is409() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("RS").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("rs@t.com").build());
        provisioning.provision(org.getId(), schema);
        String token = adminJwt(org, schema);
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(token); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> list = restTemplate.exchange("/baas/v1/roles", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        List<Map<String,Object>> data = (List<Map<String,Object>>) list.getBody().get("data");
        assertThat(data).extracting(m -> m.get("name")).contains("PARTNER_MAKER").doesNotContain("TELLER");

        String adminRoleId = data.stream().filter(m -> m.get("name").equals("PARTNER_ADMIN"))
            .findFirst().orElseThrow().get("id").toString();
        ResponseEntity<Map> del = restTemplate.exchange("/baas/v1/roles/" + adminRoleId,
            HttpMethod.DELETE, new HttpEntity<>(h), Map.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // built-in protected
    }
}
