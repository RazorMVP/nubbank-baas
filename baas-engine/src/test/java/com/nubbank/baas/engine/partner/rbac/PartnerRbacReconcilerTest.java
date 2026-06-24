package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerRbacReconcilerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository userRepo;
    @Autowired PartnerApiKeyRepository keyRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired PartnerRbacReconciler reconciler;

    @Test
    void reconcile_grantsAdminRole_and_grandfathersKeys_idempotently() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("R").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema).contactEmail("r@t.com").build());
        provisioning.provision(org.getId(), schema); // creates schema + runs V1..V7
        PartnerUser admin = userRepo.save(PartnerUser.builder().organization(org)
            .email("admin@r.com").passwordHash("x").role("PARTNER_ADMIN").active(true).build());
        PartnerApiKey key = keyRepo.save(PartnerApiKey.builder().organization(org)
            .keyHash(UUID.randomUUID().toString()).keyPrefix("cba_x").scopes("[]")
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX).active(true).build());

        reconciler.reconcileOrg(org);
        reconciler.reconcileOrg(org); // idempotent

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "SANDBOX","SANDBOX","JWT",null));
        try {
            assertThat(userRoleRepo.existsSuperuserRoleByUserId(admin.getId())).isTrue();
        } finally { PartnerContext.clear(); }
        assertThat(keyRepo.findById(key.getId()).orElseThrow().getScopes()).isEqualTo("[\"*\"]");
    }
}
