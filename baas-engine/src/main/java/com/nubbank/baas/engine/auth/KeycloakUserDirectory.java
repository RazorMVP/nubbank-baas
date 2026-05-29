package com.nubbank.baas.engine.auth;

import java.util.Set;

/** Lists the active operator subjects for a partner realm. Live Keycloak impl is DEF-1C-17. */
public interface KeycloakUserDirectory {
    /** @return the set of active (enabled, existing) Keycloak subjects for the partner's realm. */
    Set<String> activeSubjects(String partnerId);
}
