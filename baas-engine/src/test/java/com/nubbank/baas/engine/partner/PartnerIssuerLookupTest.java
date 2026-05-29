package com.nubbank.baas.engine.partner;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerIssuerLookupTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;

    @Test
    void findsOrgByKeycloakIssuer() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        String issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        orgRepo.save(PartnerOrganization.builder()
            .name("Issuer Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("i@test.com").build());

        assertThat(orgRepo.findByKeycloakIssuer(issuer)).isPresent();
        assertThat(orgRepo.findByKeycloakIssuer("https://nope")).isEmpty();
    }

    @Test
    void statusGatesAuth() {
        assertThat(PartnerStatus.BASIC.isActiveForAuth()).isTrue();
        assertThat(PartnerStatus.SANDBOX.isActiveForAuth()).isTrue();
        assertThat(PartnerStatus.SUSPENDED.isActiveForAuth()).isFalse();
        assertThat(PartnerStatus.PENDING_REVIEW.isActiveForAuth()).isFalse();
    }
}
