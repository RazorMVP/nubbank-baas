package com.nubbank.baas.engine.accounting;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AccountingRulesControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private GlAccountRepository glAccountRepo;

    private String jwt;
    private UUID debitGlId;
    private UUID creditGlId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Rules Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("rules@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "rules@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "Rules Test",
            schemaName, "SANDBOX", "SANDBOX");

        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName, "SANDBOX", "SANDBOX", "TEST"));
        debitGlId = glAccountRepo.save(GlAccount.builder()
            .name("Loans Receivable").glCode("1100-" + UUID.randomUUID().toString().substring(0, 6))
            .accountType(GlAccountType.ASSET)
            .accountUsage("DETAIL").manualJournalEntriesAllowed(true).disabled(false).build()).getId();
        creditGlId = glAccountRepo.save(GlAccount.builder()
            .name("Interest Income").glCode("4100-" + UUID.randomUUID().toString().substring(0, 6))
            .accountType(GlAccountType.INCOME)
            .accountUsage("DETAIL").manualJournalEntriesAllowed(true).disabled(false).build()).getId();
        PartnerContext.clear();
    }

    @Test
    void createAccountingRule_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/accountingrules",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Loan Disbursement Rule",
                "debitAccountId", debitGlId.toString(),
                "creditAccountId", creditGlId.toString()), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("name")).isEqualTo("Loan Disbursement Rule");
    }

    @Test
    void createProvisioningCriteria_withDefinitions_returns201() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> resp = restTemplate.exchange("/baas/v1/provisioningcriteria",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "name", "Standard IFRS 9",
                "definitions", List.of(
                    Map.of("categoryName", "STANDARD", "minAge", 0, "maxAge", 30,
                        "provisionPercentage", 1.0,
                        "liabilityAccountId", creditGlId.toString(),
                        "expenseAccountId", debitGlId.toString()),
                    Map.of("categoryName", "WATCH", "minAge", 31, "maxAge", 90,
                        "provisionPercentage", 5.0,
                        "liabilityAccountId", creditGlId.toString(),
                        "expenseAccountId", debitGlId.toString())
                )), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("name")).isEqualTo("Standard IFRS 9");
    }
}
