package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class V8MigrationTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PermissionRepository permissionRepo;

    @Test
    void v8_seedsApproveAccountAndManagePermissions_andGrantsApproverApproveAccount() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("MC").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("mc@t.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            List<String> codes = permissionRepo.findAllCodes();
            assertThat(codes).contains("APPROVE_ACCOUNT", "MANAGE_MAKER_CHECKER");

            // CREATE_ACCOUNT is now maker-checkable
            Permission createAccount = permissionRepo.findAll().stream()
                .filter(p -> p.getCode().equals("CREATE_ACCOUNT")).findFirst().orElseThrow();
            assertThat(createAccount.isCanMakerChecker()).isTrue();

            // PARTNER_APPROVER holds APPROVE_ACCOUNT
            Role approver = roleRepo.findByName("PARTNER_APPROVER").orElseThrow();
            assertThat(approver.getPermissions().stream().map(Permission::getCode))
                .contains("APPROVE_ACCOUNT");
        } finally {
            PartnerContext.clear();
        }
    }
}
