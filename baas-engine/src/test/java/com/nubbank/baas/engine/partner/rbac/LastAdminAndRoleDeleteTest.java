package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 (M2) — last-admin lockout counts only ACTIVE admins:
 *   - Deactivating the sole ACTIVE admin (with an inactive second admin) → 409
 *   - Deactivating one admin when another is also active → 200
 *
 * T2 (role-in-use) — DELETE /roles/{id} returns 409 when the role is assigned to a user,
 *   and 200 when the role is not assigned to anyone.
 */
class LastAdminAndRoleDeleteTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository         userRepo;
    @Autowired TenantProvisioningService     provisioning;

    // ─── helpers ────────────────────────────────────────────────────────────

    record Org(PartnerOrganization org, String schema) {}

    private Org newOrg() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Test").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("t@t.com").build());
        provisioning.provision(org.getId(), schema);
        return new Org(org, schema);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** Save a PartnerUser with a specific active state (bypasses the @PrePersist active=true default). */
    private PartnerUser saveUser(PartnerOrganization org, String email, boolean active) {
        PartnerUser u = PartnerUser.builder()
            .organization(org)
            .email(email)
            .passwordHash("$2a$10$dummyhashfortest")
            .role("PARTNER_ADMIN")
            .active(true) // PrePersist forces this; we flip it after save
            .build();
        u = userRepo.save(u);
        if (!active) {
            u.setActive(false);
            u = userRepo.save(u);
        }
        return u;
    }

    // ─── T1 — last-active-admin guard ────────────────────────────────────────

    /**
     * T1a — admin1 is ACTIVE, admin2 is INACTIVE.
     * Deactivating admin1 → 409 (admin1 is the only active admin; admin2 must not count).
     */
    @Test
    void t1_deactivatingLastActiveAdmin_with_inactiveSecondAdmin_returns409() {
        Org o = newOrg();

        // admin1: active=true, grant PARTNER_ADMIN
        PartnerUser admin1 = saveUser(o.org(), "admin1@t.com", true);
        grantAdmin(o.schema(), admin1.getId());

        // admin2: save active=true first (PrePersist), then flip to inactive
        PartnerUser admin2 = saveUser(o.org(), "admin2@t.com", false);
        grantAdmin(o.schema(), admin2.getId());

        // JWT for admin1 (the active admin)
        String jwt = partnerJwtService.issue(
            admin1.getId().toString(), "admin1@t.com", "PARTNER_ADMIN",
            o.org().getId().toString(), o.org().getName(), o.schema(),
            o.org().getTier().name(), o.org().getEnvironment().name());

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/partner-users/" + admin1.getId() + "/deactivate",
            HttpMethod.POST,
            new HttpEntity<>(bearer(jwt)),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Deactivating the sole ACTIVE admin must be rejected with 409 even when an inactive admin exists")
            .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * T1b (positive control) — admin1 and admin2 are both ACTIVE.
     * Deactivating admin1 → 200 (admin2 remains as an active backstop).
     */
    @Test
    void t1_deactivatingAdmin_when_anotherActiveAdminExists_returns200() {
        Org o = newOrg();

        // Both admins active
        PartnerUser admin1 = saveUser(o.org(), "a1@t.com", true);
        grantAdmin(o.schema(), admin1.getId());

        PartnerUser admin2 = saveUser(o.org(), "a2@t.com", true);
        grantAdmin(o.schema(), admin2.getId());

        String jwt = partnerJwtService.issue(
            admin1.getId().toString(), "a1@t.com", "PARTNER_ADMIN",
            o.org().getId().toString(), o.org().getName(), o.schema(),
            o.org().getTier().name(), o.org().getEnvironment().name());

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/partner-users/" + admin1.getId() + "/deactivate",
            HttpMethod.POST,
            new HttpEntity<>(bearer(jwt)),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Deactivating one admin while another active admin exists must succeed")
            .isEqualTo(HttpStatus.OK);
    }

    // ─── T2 — role-in-use delete guard ───────────────────────────────────────

    /**
     * T2 — DELETE /roles/{id} when the role IS assigned to a user → 409 ROLE_IN_USE.
     * DELETE /roles/{id2} when the role is NOT assigned to anyone → 200.
     */
    @SuppressWarnings("unchecked")
    @Test
    void t2_deleteAssignedRole_returns409_deleteUnassignedRole_returns200() {
        Org o = newOrg();
        String jwt = adminJwt(o.org(), o.schema());

        // Create a custom role "TEMP" via the API
        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/baas/v1/roles", HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "TEMP_ASSIGNED", "description", "in-use role"),
                bearer(jwt)),
            Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tempRoleId = ((Map<String, Object>) createResp.getBody().get("data")).get("id").toString();

        // Assign the role to a newly created partner user
        ResponseEntity<Map> userResp = restTemplate.exchange(
            "/baas/v1/partner-users", HttpMethod.POST,
            new HttpEntity<>(
                Map.of("email", "tempholder@t.com", "password", "password123",
                    "roleIds", List.of(tempRoleId)),
                bearer(jwt)),
            Map.class);
        assertThat(userResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // DELETE the assigned role → expect 409
        ResponseEntity<Map> deleteAssigned = restTemplate.exchange(
            "/baas/v1/roles/" + tempRoleId, HttpMethod.DELETE,
            new HttpEntity<>(bearer(jwt)),
            Map.class);
        assertThat(deleteAssigned.getStatusCode())
            .as("Deleting an in-use role must return 409 CONFLICT")
            .isEqualTo(HttpStatus.CONFLICT);

        // Create a second custom role that is NOT assigned to anyone
        ResponseEntity<Map> create2 = restTemplate.exchange(
            "/baas/v1/roles", HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "TEMP_UNASSIGNED", "description", "unused role"),
                bearer(jwt)),
            Map.class);
        assertThat(create2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String unassignedRoleId = ((Map<String, Object>) create2.getBody().get("data")).get("id").toString();

        // DELETE the unassigned role → expect 200
        ResponseEntity<Map> deleteUnassigned = restTemplate.exchange(
            "/baas/v1/roles/" + unassignedRoleId, HttpMethod.DELETE,
            new HttpEntity<>(bearer(jwt)),
            Map.class);
        assertThat(deleteUnassigned.getStatusCode())
            .as("Deleting an unassigned custom role must succeed with 200")
            .isEqualTo(HttpStatus.OK);
    }
}
