package com.nubbank.baas.engine.auth.keycloak;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds one {@link NimbusJwtDecoder} per Keycloak issuer from its JWKS endpoint and caches it.
 * Nimbus refreshes the JWK set internally; cache invalidation on realm-key rotation is DEF-1C-09.
 */
@Component
public class JwksOperatorJwtDecoderFactory implements OperatorJwtDecoderFactory {

    private final Map<String, JwtDecoder> cache = new ConcurrentHashMap<>();

    @Override
    public JwtDecoder decoderFor(String issuer) {
        return cache.computeIfAbsent(issuer, iss -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri(iss)).build();
            return decoder;
        });
    }

    String jwksUri(String issuer) {
        return issuer + "/protocol/openid-connect/certs";
    }
}
