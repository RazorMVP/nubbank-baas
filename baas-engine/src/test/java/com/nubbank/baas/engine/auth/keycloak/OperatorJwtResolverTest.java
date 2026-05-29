package com.nubbank.baas.engine.auth.keycloak;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OperatorJwtResolverTest extends AbstractIntegrationTest {

    static final TestJwks JWKS = new TestJwks();

    @TestConfiguration
    static class StubDecoderConfig {
        @Bean @Primary OperatorJwtDecoderFactory stubFactory() {
            return issuer -> JWKS.decoder(); // every issuer validates against the test key
        }
    }

    @Autowired OperatorJwtResolver resolver;
    @Autowired PartnerOrganizationRepository orgRepo;

    private PartnerOrganization activeOrg(String issuer) {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        return orgRepo.save(PartnerOrganization.builder()
            .name("Op Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("op@test.com").build());
    }

    @Test
    void resolvesActivePartnerOperator() {
        String issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        PartnerOrganization org = activeOrg(issuer);
        String sub = UUID.randomUUID().toString();

        PartnerContext ctx = resolver.resolve(JWKS.sign(issuer, sub, 300));

        assertThat(ctx.partnerId()).isEqualTo(org.getId().toString());
        assertThat(ctx.schemaName()).isEqualTo(org.getSchemaName());
        assertThat(ctx.authMode()).isEqualTo("OPERATOR_JWT");
        assertThat(ctx.userId()).isEqualTo(sub);
    }

    @Test
    void rejectsUnknownIssuer() {
        assertThatThrownBy(() ->
            resolver.resolve(JWKS.sign("https://evil/realms/x", UUID.randomUUID().toString(), 300)))
            .isInstanceOf(BaasException.class);
    }

    @Test
    void rejectsSuspendedPartner() {
        String issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        PartnerOrganization org = activeOrg(issuer);
        org.setStatus(PartnerStatus.SUSPENDED);
        orgRepo.save(org);
        assertThatThrownBy(() -> resolver.resolve(JWKS.sign(issuer, UUID.randomUUID().toString(), 300)))
            .isInstanceOf(BaasException.class);
    }
}
