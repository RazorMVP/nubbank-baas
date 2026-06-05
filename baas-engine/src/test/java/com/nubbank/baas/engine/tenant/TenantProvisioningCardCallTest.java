package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Stage 5 Task 12 — the engine triggers card provisioning after its own migrations, and a card
 * failure fails the whole provisioning (no half-provisioned partner). {@code CardProvisioningClient}
 * is mocked here (it is disabled in the test profile for all other tests).
 */
class TenantProvisioningCardCallTest extends AbstractIntegrationTest {

    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private CardProvisioningClient cardProvisioningClient;

    /** schema_provision_log.partner_id has an FK to partner_organizations — create a real org. */
    private UUID newPartner() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        return orgRepo.save(PartnerOrganization.builder()
            .name("Provision Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("prov@test.com").build()).getId();
    }

    private String schemaOf(UUID partnerId) {
        return orgRepo.findById(partnerId).orElseThrow().getSchemaName();
    }

    @Test
    void provisionCallsCardAfterMigrations() {
        UUID partnerId = newPartner();
        String schema = schemaOf(partnerId);

        provisioningService.provision(partnerId, schema);

        verify(cardProvisioningClient).provision(eq(partnerId), eq(schema));
        Long success = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public.schema_provision_log WHERE schema_name = ? AND status = 'SUCCESS'",
            Long.class, schema);
        assertThat(success).isEqualTo(1L);
    }

    @Test
    void cardFailureFailsProvisioning() {
        UUID partnerId = newPartner();
        String schema = schemaOf(partnerId);
        doThrow(new RuntimeException("card down")).when(cardProvisioningClient).provision(any(), any());

        assertThatThrownBy(() -> provisioningService.provision(partnerId, schema))
            .isInstanceOf(RuntimeException.class);

        Long failed = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public.schema_provision_log WHERE schema_name = ? AND status = 'FAILED'",
            Long.class, schema);
        assertThat(failed).isEqualTo(1L);
    }
}
