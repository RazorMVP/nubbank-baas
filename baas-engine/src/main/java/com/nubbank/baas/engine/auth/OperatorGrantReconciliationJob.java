package com.nubbank.baas.engine.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly sweep: revokes RBAC grants whose Keycloak subject is no longer active (orphaned-grant mitigation).
 * In stub-mode the directory returns empty -> nothing is pruned. The live cross-schema sweep
 * (iterate active partner schemas, diff user_roles against the realm) is DEF-1C-17.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorGrantReconciliationJob {

    private final KeycloakUserDirectory directory;

    @Scheduled(cron = "0 30 2 * * *") // 02:30 daily
    public void reconcile() {
        log.info("Operator grant reconciliation tick (stub-mode: no-op until DEF-1C-17)");
        // Live implementation (DEF-1C-17): for each active partner schema, set PartnerContext,
        // load distinct user_roles.user_id, compare with directory.activeSubjects(partnerId),
        // and revokeAllGrants(sub) for each subject absent from the directory.
    }
}
