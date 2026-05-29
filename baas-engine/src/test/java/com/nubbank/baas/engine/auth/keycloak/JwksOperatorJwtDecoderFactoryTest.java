package com.nubbank.baas.engine.auth.keycloak;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.assertj.core.api.Assertions.assertThat;

class JwksOperatorJwtDecoderFactoryTest {

    @Test
    void cachesDecoderPerIssuer() {
        JwksOperatorJwtDecoderFactory factory = new JwksOperatorJwtDecoderFactory();
        String issuer = "https://auth.nubbank.test/realms/baas-partner-abc";

        JwtDecoder first  = factory.decoderFor(issuer);
        JwtDecoder second = factory.decoderFor(issuer);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first); // same instance — cached, no re-fetch
    }

    @Test
    void derivesJwksUriFromIssuer() {
        JwksOperatorJwtDecoderFactory factory = new JwksOperatorJwtDecoderFactory();
        assertThat(factory.jwksUri("https://h/realms/r"))
            .isEqualTo("https://h/realms/r/protocol/openid-connect/certs");
    }
}
