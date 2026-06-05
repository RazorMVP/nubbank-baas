package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code card_auth_debit} table + entity persist and resolve by {@code auth_key}
 * within a provisioned tenant schema (Stage 5 Task 2). The V4 tenant migration must have run
 * for the persist to succeed — so this also exercises that the migration applies.
 */
class CardAuthDebitRepositoryTest extends AbstractIntegrationTest {

    @Autowired private CardAuthDebitRepository repo;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("CardAuthDebit Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("cad@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        // Leave context set for the test body; AbstractIntegrationTest clears it in @AfterEach.
        PartnerContext.set(new PartnerContext(
            org.getId().toString(), schemaName, "SANDBOX", "SANDBOX", "TEST", null));
    }

    @Test
    void persistsAndFindsByAuthKey() {
        repo.save(CardAuthDebit.builder()
            .authKey("000001|TERM0001|0604120000").accountId(UUID.randomUUID())
            .amount(new BigDecimal("100.0000")).currencyCode("NGN")
            .outcome(CardAuthOutcome.DEBITED).reversed(false).build());

        assertThat(repo.findByAuthKey("000001|TERM0001|0604120000"))
            .isPresent()
            .get()
            .satisfies(row -> {
                assertThat(row.getOutcome()).isEqualTo(CardAuthOutcome.DEBITED);
                assertThat(row.getCreatedAt()).isNotNull();
                assertThat(row.isReversed()).isFalse();
            });
        assertThat(repo.findByAuthKey("does-not-exist")).isEmpty();
    }
}
