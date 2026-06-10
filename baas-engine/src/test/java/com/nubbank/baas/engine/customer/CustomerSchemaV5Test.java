package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerSchemaV5Test extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void v5_addsKycEventsTableAndNameSearchTokensColumn() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("V5").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("v5@test.com").build());
        provisioningService.provision(org.getId(), schema);

        assertThat(jdbc.queryForObject("SELECT to_regclass(?)", String.class,
            schema + ".customer_kyc_events")).isNotNull();
        Integer col = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
            + "WHERE table_schema = ? AND table_name = 'customers' AND column_name = 'name_search_tokens'",
            Integer.class, schema);
        assertThat(col).isEqualTo(1);
    }
}
