package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerRbacReconciler implements SmartInitializingSingleton {

    private final PartnerOrganizationRepository orgRepo;
    private final PartnerUserRepository userRepo;
    private final PartnerApiKeyRepository keyRepo;
    private final RoleRepository roleRepo;
    private final UserRoleRepository userRoleRepo;
    private final TenantProvisioningService provisioning;

    /** Blocking startup backfill across all existing tenants (before the web server serves). */
    @Override
    public void afterSingletonsInstantiated() {
        for (PartnerOrganization org : orgRepo.findAll()) {
            try { reconcileOrg(org); }
            catch (Exception ex) { log.error("RBAC reconcile failed for org {}: {}", org.getId(), ex.getMessage()); }
        }
    }

    /**
     * Migrate the tenant schema (idempotent), set the PartnerContext, then grant existing
     * users PARTNER_ADMIN and grandfather empty API keys to ["*"].
     *
     * NOT @Transactional itself — the PartnerContext must be set BEFORE Hibernate opens
     * a connection (which happens at the @Transactional proxy boundary). The actual DB
     * work is delegated to {@link #doReconcile(PartnerOrganization)} which IS transactional
     * and is invoked only after the context is in place.
     */
    public void reconcileOrg(PartnerOrganization org) {
        provisioning.migrateTenant(org.getSchemaName()); // Flyway: idempotent, own connections
        PartnerContext.set(new PartnerContext(org.getId().toString(), org.getSchemaName(),
            org.getTier().name(), org.getEnvironment().name(), "JWT", null));
        try {
            doReconcile(org);
        } finally {
            PartnerContext.clear();
        }
    }

    /**
     * Transactional DB work — called only after PartnerContext is set so Hibernate routes
     * all queries to the correct tenant schema.
     */
    @Transactional
    public void doReconcile(PartnerOrganization org) {
        Role admin = roleRepo.findByName(PartnerRoles.ADMIN).orElseThrow();
        for (PartnerUser u : userRepo.findByOrganization_Id(org.getId())) {
            if (userRoleRepo.findById(new UserRoleId(u.getId(), admin.getId())).isEmpty()) {
                userRoleRepo.save(UserRole.builder().userId(u.getId()).role(admin).build());
            }
        }
        for (PartnerApiKey k : keyRepo.findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(org.getId())) {
            if (k.getScopes() == null || k.getScopes().replaceAll("\\s", "").equals("[]")) {
                k.setScopes("[\"*\"]");
                keyRepo.save(k);
            }
        }
    }
}
