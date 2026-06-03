package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-tenant purge deletes rows older than the retention window while
 * keeping fresh rows, in the correct partner schema.
 */
class IdempotencyPurgeJobTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private AuthorizationIdempotencyRepository idemRepo;
    @Autowired private IdempotencyPurgeJob purgeJob;

    @Test
    void purge_deletesOldRows_keepsFreshRows_perTenant() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);

        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey("OLD|TERM0001|0101120000").decision("APPROVE").responseCode("00")
                .message("old").reversed(false)
                .createdAt(Instant.now().minus(48, ChronoUnit.HOURS)).build());
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey("NEW|TERM0001|0101120000").decision("APPROVE").responseCode("00")
                .message("new").reversed(false)
                .createdAt(Instant.now()).build());
        } finally {
            PartnerContext.clear();
        }

        purgeJob.purgeAllTenants();

        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            assertThat(idemRepo.findByIdemKey("OLD|TERM0001|0101120000")).isEmpty();
            assertThat(idemRepo.findByIdemKey("NEW|TERM0001|0101120000")).isPresent();
        } finally {
            PartnerContext.clear();
        }
    }
}
