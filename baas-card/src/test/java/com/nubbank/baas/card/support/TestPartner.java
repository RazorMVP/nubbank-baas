package com.nubbank.baas.card.support;

import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.partner.PartnerEnvironment;
import com.nubbank.baas.card.partner.PartnerOrganization;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.partner.PartnerStatus;
import com.nubbank.baas.card.partner.PartnerTier;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import java.util.UUID;

/**
 * Test helper: persists a {@link PartnerOrganization} row in {@code public},
 * provisions its card tenant schema (+ sandbox), and issues an HMAC partner JWT
 * for it.
 */
public class TestPartner {

    public final UUID orgId;
    public final String schemaName;
    public final String jwt;

    private TestPartner(UUID orgId, String schemaName, String jwt) {
        this.orgId = orgId;
        this.schemaName = schemaName;
        this.jwt = jwt;
    }

    public static TestPartner create(PartnerOrganizationRepository orgRepo,
                                     TenantProvisioningService provisioningService,
                                     PartnerJwtService jwtService) {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Test Partner")
            .status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName)
            .contactEmail("test@partner.com")
            .build();
        org = orgRepo.save(org);
        provisioningService.provision(org.getId(), schemaName);

        // Matches PartnerJwtService.issue(userId, email, role, orgId, orgName,
        //                                 schemaName, tier, environment)
        String jwt = jwtService.issue(UUID.randomUUID().toString(), "test@partner.com",
            "PARTNER_ADMIN", org.getId().toString(), "Test Partner",
            schemaName, "SANDBOX", "SANDBOX");

        return new TestPartner(org.getId(), schemaName, jwt);
    }
}
