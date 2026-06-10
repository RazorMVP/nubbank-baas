package com.nubbank.baas.engine.operator;

import java.util.List;

/**
 * The authenticated operator's identity + resolved authorities (DEF-1C-28).
 *
 * <p>{@code authorities} are the permission codes the operator may exercise this request —
 * resolved server-side (they are NOT in the Keycloak token), so a PKCE backoffice can fetch
 * them here rather than guessing from a token claim. {@code roles} are the named roles granted
 * via {@code user_roles} (populated only for Keycloak operator tokens; empty for first-party
 * partner credentials, which carry full tenant authority without role rows).
 */
public record OperatorMeResponse(
    String operatorId,
    String authMode,
    String partnerId,
    String tier,
    String environment,
    List<String> roles,
    List<String> authorities
) {}
