package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalServiceTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private AuthorizationIdempotencyRepository idemRepo;
    @Autowired private ReversalService reversalService;

    @Test
    void reverse_locatesOriginal_marksReversed() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000001", "TERM0001", "0101120000");

        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000001", "TERM0001", "0101120000"));

        assertThat(resp.located()).isTrue();
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            assertThat(idemRepo.findByIdemKey("000001|TERM0001|0101120000").get().isReversed()).isTrue();
        } finally {
            PartnerContext.clear();
        }
    }

    @Test
    void reverse_noOriginal_returnsNotLocated() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "999999", "TERM0001", "0101120000"));
        assertThat(resp.located()).isFalse();
    }

    @Test
    void reverse_alreadyReversed_isIdempotentTrue() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000002", "TERM0001", "0101120000");
        ReversalRequest req = new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000002", "TERM0001", "0101120000");
        assertThat(reversalService.reverse(req).located()).isTrue();
        assertThat(reversalService.reverse(req).located()).isTrue();
    }

    @Test
    void reverse_clearsContext() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        assertThat(PartnerContext.get()).isNull();
        reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "777", "T", "0101120000"));
        assertThat(PartnerContext.get()).isNull();
    }

    private void seedOriginal(TestPartner partner, String stan, String term, String dts) {
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey(stan + "|" + term + "|" + dts)
                .decision("APPROVE").responseCode("00").message("Approved").reversed(false).build());
        } finally {
            PartnerContext.clear();
        }
    }
}
