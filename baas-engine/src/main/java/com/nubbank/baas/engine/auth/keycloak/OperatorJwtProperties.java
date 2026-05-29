package com.nubbank.baas.engine.auth.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Keycloak operator-auth config. {@code admin-issuer} is the NubBank staff realm issuer. */
@ConfigurationProperties(prefix = "app.keycloak")
public record OperatorJwtProperties(String adminIssuer) {}
