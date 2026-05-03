package com.nubbank.baas.engine.twofa;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TwoFactorControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private TwoFactorService twoFactorService;

    private String jwt;
    private UUID userId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("2FA Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("twofa@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        userId = UUID.randomUUID();
        jwt = jwtService.issue(userId.toString(), "twofa@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "2FA Test",
            schemaName, "SANDBOX", "SANDBOX");
        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
    }

    @Test
    void generate_and_verify_otp() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> genResp = restTemplate.exchange("/baas/v1/twofactor/generate",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", userId.toString(), "deliveryMethod", "EMAIL",
                "recipient", "test@example.com"), h), Map.class);
        assertThat(genResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenId = ((Map<?, ?>) genResp.getBody().get("data")).get("tokenId").toString();

        String otp = twoFactorService.getPlaintextOtpForTest(UUID.fromString(tokenId));

        ResponseEntity<Map> verifyResp = restTemplate.exchange("/baas/v1/twofactor/verify",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("tokenId", tokenId, "otp", otp), h), Map.class);
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) ((Map<?, ?>) verifyResp.getBody().get("data")).get("verified"))
            .isTrue();
    }

    @Test
    void verify_wrong_otp_returns400() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> genResp = restTemplate.exchange("/baas/v1/twofactor/generate",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", userId.toString(), "deliveryMethod", "EMAIL",
                "recipient", "test@example.com"), h), Map.class);
        String tokenId = ((Map<?, ?>) genResp.getBody().get("data")).get("tokenId").toString();

        ResponseEntity<Map> verifyResp = restTemplate.exchange("/baas/v1/twofactor/verify",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("tokenId", tokenId, "otp", "000000"), h), Map.class);
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
