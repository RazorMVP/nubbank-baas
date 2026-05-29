package com.nubbank.baas.engine.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Stub-mode directory: reports no specific subjects; the reconciliation job treats an empty
 *  directory as "unknown — prune nothing", so stub-mode never revokes real grants. */
@Component
@Profile("!live-keycloak")
public class StubKeycloakUserDirectory implements KeycloakUserDirectory {
    @Override public Set<String> activeSubjects(String partnerId) { return Set.of(); }
}
