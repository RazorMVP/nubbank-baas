package com.nubbank.baas.engine.accounting;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GlAccountingControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String jwt;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("GL Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("gl@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = adminJwt(org, schemaName);
    }

    @Test
    void createGlAccount_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/glaccounts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Cash and Cash Equivalents",
                "glCode", "1001", "accountType", "ASSET", "accountUsage", "DETAIL"), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("glCode")).isEqualTo("1001");
    }

    @Test
    void postManualJournalEntry_balanced_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        String debitId = createGlAccount(h, "Cash", "1000", "ASSET");
        String creditId = createGlAccount(h, "Revenue", "4000", "INCOME");

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/journalentries",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "entryDate", "2026-04-27", "description", "Sales receipt",
                "lines", List.of(
                    Map.of("glAccountId", debitId, "entryType", "DEBIT", "amount", 50000.0),
                    Map.of("glAccountId", creditId, "entryType", "CREDIT", "amount", 50000.0)
                )), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void postManualJournalEntry_unbalanced_returns400() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        String debitId = createGlAccount(h, "Cash2", "1002", "ASSET");
        String creditId = createGlAccount(h, "Revenue2", "4001", "INCOME");

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/journalentries",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "entryDate", "2026-04-27", "description", "Unbalanced",
                "lines", List.of(
                    Map.of("glAccountId", debitId, "entryType", "DEBIT", "amount", 50000.0),
                    Map.of("glAccountId", creditId, "entryType", "CREDIT", "amount", 30000.0)
                )), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String createGlAccount(HttpHeaders h, String name, String code, String type) {
        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/glaccounts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", name, "glCode", code, "accountType", type, "accountUsage", "DETAIL"), h), Map.class);
        return ((Map<?, ?>) resp.getBody().get("data")).get("id").toString();
    }
}
