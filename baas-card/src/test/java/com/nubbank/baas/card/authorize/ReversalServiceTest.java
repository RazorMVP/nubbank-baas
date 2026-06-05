package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.engine.EngineClient;
import com.nubbank.baas.card.engine.dto.CardCreditResult;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalServiceTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private AuthorizationIdempotencyRepository idemRepo;
    @Autowired private ReversalService reversalService;

    @MockitoBean private EngineClient engineClient;

    @BeforeEach
    void stubEngineCredit() {
        // Default: the engine locates + credits the original. Specific tests re-stub.
        Mockito.when(engineClient.cardCredit(Mockito.any())).thenReturn(new CardCreditResult(true));
    }

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
    void reverse_approveOriginal_creditsEngine() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000010", "TERM0001", "0101120000", "APPROVE");

        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000010", "TERM0001", "0101120000"));

        assertThat(resp.located()).isTrue();
        Mockito.verify(engineClient).cardCredit(Mockito.any());
    }

    @Test
    void reverse_declineOriginal_locatedWithoutCredit() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000011", "TERM0001", "0101120000", "DECLINE");

        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000011", "TERM0001", "0101120000"));

        assertThat(resp.located()).isTrue();   // located, but nothing to credit
        Mockito.verify(engineClient, Mockito.never()).cardCredit(Mockito.any());
    }

    @Test
    void reverse_engineUnreachableOnApprove_notLocated_andDoesNotFlip() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        seedOriginal(partner, "000012", "TERM0001", "0101120000", "APPROVE");
        Mockito.when(engineClient.cardCredit(Mockito.any())).thenReturn(new CardCreditResult(false));

        ReversalResponse resp = reversalService.reverse(new ReversalRequest(
            partner.orgId.toString(), partner.schemaName, "000012", "TERM0001", "0101120000"));

        assertThat(resp.located()).isFalse();   // fail-closed → RC 25
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            assertThat(idemRepo.findByIdemKey("000012|TERM0001|0101120000").get().isReversed())
                .as("reversed must NOT be flipped when the credit could not complete")
                .isFalse();
        } finally {
            PartnerContext.clear();
        }
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
        seedOriginal(partner, stan, term, dts, "APPROVE");
    }

    private void seedOriginal(TestPartner partner, String stan, String term, String dts, String decision) {
        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "INTERNAL", "PRODUCTION", "INTERNAL", null));
            String rc = "APPROVE".equals(decision) ? "00" : "05";
            idemRepo.save(AuthorizationIdempotency.builder()
                .idemKey(stan + "|" + term + "|" + dts)
                .decision(decision).responseCode(rc).message(decision).reversed(false).build());
        } finally {
            PartnerContext.clear();
        }
    }
}
