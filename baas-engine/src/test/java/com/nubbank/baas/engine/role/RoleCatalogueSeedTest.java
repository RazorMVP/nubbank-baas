package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RoleCatalogueSeedTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;

    @Test
    void provisionSeeds30Roles() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Seed Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("s@test.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "BASIC",
            "PRODUCTION", "API_KEY", null));
        try {
            List<String> names = roleRepo.findAll().stream().map(Role::getName).toList();
            assertThat(names).containsExactlyInAnyOrder(
                "PARTNER_ADMIN","BRANCH_MANAGER","OPERATIONS_MANAGER","PRODUCT_MANAGER","SYSTEM_CONFIGURATOR",
                "CUSTOMER_SERVICE_OFFICER","RELATIONSHIP_MANAGER","TELLER","HEAD_TELLER","CUSTOMER_SUPPORT",
                "ACCOUNT_OFFICER","KYC_OFFICER","LOAN_OFFICER","CREDIT_ANALYST","CREDIT_APPROVER",
                "COLLECTIONS_OFFICER","LOAN_OPERATIONS_OFFICER","PAYMENTS_OFFICER","REMITTANCE_OFFICER","TREASURY_OFFICER",
                "CARD_OPERATIONS_OFFICER","RECONCILIATION_OFFICER","COMPLIANCE_OFFICER","AML_ANALYST","FRAUD_ANALYST",
                "RISK_OFFICER","FINANCE_OFFICER","FINANCIAL_CONTROLLER","INTERNAL_AUDITOR","AUDITOR_READONLY");
            Role teller = roleRepo.findAll().stream()
                .filter(r -> r.getName().equals("TELLER")).findFirst().orElseThrow();
            assertThat(teller.getPermissions()).extracting("code").contains("READ_ACCOUNT","DEPOSIT","WITHDRAW");
            Permission approveLoan = permRepo.findAll().stream()
                .filter(p -> p.getCode().equals("APPROVE_LOAN")).findFirst().orElseThrow();
            assertThat(approveLoan.isCanMakerChecker()).isTrue();
        } finally { PartnerContext.clear(); }
    }
}
