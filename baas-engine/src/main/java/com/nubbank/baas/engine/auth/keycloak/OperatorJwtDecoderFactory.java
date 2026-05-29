package com.nubbank.baas.engine.auth.keycloak;

import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Returns a JWKS-backed decoder for a given Keycloak issuer. Test impls supply in-memory keys. */
public interface OperatorJwtDecoderFactory {
    JwtDecoder decoderFor(String issuer);
}
