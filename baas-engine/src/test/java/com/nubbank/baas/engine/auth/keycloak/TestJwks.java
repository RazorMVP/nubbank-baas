package com.nubbank.baas.engine.auth.keycloak;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.util.Date;

/** Generates an in-memory RSA key + matching offline {@link JwtDecoder} for operator-JWT tests. */
public final class TestJwks {
    private final RSAKey key;
    public TestJwks() {
        try { this.key = new RSAKeyGenerator(2048).keyID("test-kid").generate(); }
        catch (JOSEException e) { throw new RuntimeException(e); }
    }

    public String sign(String issuer, String sub, long expiresInSec) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(sub)
                .expirationTime(new Date(System.currentTimeMillis() + expiresInSec * 1000))
                .issueTime(new Date()).build();
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) { throw new RuntimeException(e); }
    }

    public JwtDecoder decoder() {
        try {
            DefaultJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> src = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
            proc.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, src));
            return new NimbusJwtDecoder(proc);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
