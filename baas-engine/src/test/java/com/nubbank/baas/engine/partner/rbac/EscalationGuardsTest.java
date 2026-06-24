package com.nubbank.baas.engine.partner.rbac;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a non-superuser delegate who legitimately holds MANAGE_* permissions
 * (and therefore passes the @PreAuthorize gate) is still blocked at the service level
 * from escalating privilege — i.e., it cannot:
 *
 *   C1 — assign a superuser role to a new partner user
 *   C2 — issue an API key with wildcard scope or a scope the caller lacks
 *   I1a — modify the permissions of a built-in role (→ 409)
 *   I1b — grant a permission on a custom role that the caller does not hold
 */
class EscalationGuardsTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository         userRepo;
    @Autowired PermissionRepository          permRepo;
    @Autowired TenantProvisioningService     provisioning;

    // ─── shared test fixture ────────────────────────────────────────────────

    /** Everything needed for a delegate-JWT scenario in a single provisioned tenant. */
    record Fixture(
        PartnerOrganization org,
        String schema,
        /** JWT for the PARTNER_ADMIN (superuser) — used only for setup calls that must succeed. */
        String adminJwt,
        /** JWT for the delegate who holds only MANAGE_* permissions. */
        String delegateJwt,
        /** ID of a non-superuser custom role the admin created — used as the target for I1b tests. */
        UUID   customRoleId,
        /** A permission code the delegate does NOT hold — used as the escalation target. */
        String absentPermCode,
        /** ID of the permission the delegate does NOT hold. */
        UUID   absentPermId
    ) {}

    private Fixture buildFixture() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("EscTest").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .contactEmail("esc@t.com").build());
        provisioning.provision(org.getId(), schema);

        String adminToken = adminJwt(org, schema);

        // ── Build a "delegate" role that holds only MANAGE_PARTNER_USERS,
        //    MANAGE_ROLES, and MANAGE_API_KEYS — enough to reach the service
        //    layer but not enough to escalate. ──────────────────────────────
        PartnerContext.set(new PartnerContext(
            org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            // Resolve the three management permission IDs
            Set<UUID> managePermIds = new HashSet<>();
            Permission absentPerm = null;
            UUID absentPermId = null;
            String absentPermCode = null;

            for (Permission p : permRepo.findAll()) {
                if (Set.of("MANAGE_PARTNER_USERS", "MANAGE_ROLES", "MANAGE_API_KEYS")
                        .contains(p.getCode())) {
                    managePermIds.add(p.getId());
                }
                // Pick CREATE_ACCOUNT as a permission the delegate will NOT hold
                if ("CREATE_ACCOUNT".equals(p.getCode())) {
                    absentPerm   = p;
                    absentPermId = p.getId();
                    absentPermCode = p.getCode();
                }
            }
            assertThat(managePermIds).as("MANAGE_* permissions must be seeded by V7").hasSize(3);
            assertThat(absentPerm).as("CREATE_ACCOUNT permission must be seeded").isNotNull();

            // Create the delegate role with only MANAGE_* perms
            Role delegateRole = roleRepo.save(Role.builder()
                .name("DELEGATE_MANAGE")
                .description("holds only management perms")
                .builtIn(false)
                .roleScope(PartnerRoles.SCOPE_PARTNER)
                .superuser(false)
                .permissions(permRepo.findByIdIn(managePermIds))
                .build());

            // Assign it to a fresh delegate user-id
            UUID delegateUserId = UUID.randomUUID();
            userRoleRepo.save(UserRole.builder().userId(delegateUserId).role(delegateRole).build());

            // Issue a partner JWT for the delegate
            String delegateToken = partnerJwtService.issue(
                delegateUserId.toString(), "delegate@t.com", "PARTNER_USER",
                org.getId().toString(), org.getName(), schema,
                org.getTier().name(), org.getEnvironment().name());

            // Create a secondary custom role (non-built-in, empty permissions)
            // that the I1b test will try to grant CREATE_ACCOUNT to
            Role customRole = roleRepo.save(Role.builder()
                .name("CUSTOM_EMPTY")
                .description("empty custom role for escalation test target")
                .builtIn(false)
                .roleScope(PartnerRoles.SCOPE_PARTNER)
                .superuser(false)
                .build());

            return new Fixture(org, schema, adminToken, delegateToken,
                customRole.getId(), absentPermCode, absentPermId);
        } finally {
            PartnerContext.clear();
        }
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ─── test methods ────────────────────────────────────────────────────────

    /**
     * C1 — delegate tries to assign the PARTNER_ADMIN (superuser) role to a new user → 403.
     * The delegate passes @PreAuthorize("hasAuthority('MANAGE_PARTNER_USERS')") but the
     * service-level guard in resolveAssignableRoles rejects the superuser role.
     */
    @SuppressWarnings("unchecked")
    @Test
    void c1_delegateCannotAssignSuperuserRole() {
        Fixture f = buildFixture();

        // Obtain the PARTNER_ADMIN role ID via the admin JWT (the delegate can also list roles)
        ResponseEntity<Map> rolesResp = restTemplate.exchange(
            "/baas/v1/roles", HttpMethod.GET,
            new HttpEntity<>(bearer(f.adminJwt())), Map.class);
        assertThat(rolesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String adminRoleId = ((List<Map<String, Object>>) rolesResp.getBody().get("data"))
            .stream()
            .filter(r -> "PARTNER_ADMIN".equals(r.get("name")))
            .findFirst().orElseThrow()
            .get("id").toString();

        // Delegate attempts to create a user with PARTNER_ADMIN role → must be 403
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/partner-users", HttpMethod.POST,
            new HttpEntity<>(
                Map.of("email", "escalated@t.com", "password", "password123",
                    "roleIds", List.of(adminRoleId)),
                bearer(f.delegateJwt())),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Assigning a superuser role must be rejected with 403")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * C2a — delegate tries to issue an API key with wildcard scope → 403.
     */
    @Test
    void c2_delegateCannotIssueWildcardKey() {
        Fixture f = buildFixture();

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/partner-api-keys", HttpMethod.POST,
            new HttpEntity<>(
                Map.of("name", "wildcard-key", "scopes", List.of("*")),
                bearer(f.delegateJwt())),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Issuing a wildcard API key must be rejected with 403")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * C2b — delegate tries to issue an API key scoped to CREATE_ACCOUNT (which the delegate
     * does not hold) → 403.
     */
    @Test
    void c2_delegateCannotScopeKeyBeyondOwnAuthority() {
        Fixture f = buildFixture();

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/partner-api-keys", HttpMethod.POST,
            new HttpEntity<>(
                Map.of("name", "escalated-key", "scopes", List.of(f.absentPermCode())),
                bearer(f.delegateJwt())),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Scoping a key to a permission the caller lacks must be rejected with 403")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * I1a — delegate (who holds MANAGE_ROLES) tries to modify the permissions of a built-in
     * role → 409 CONFLICT.
     */
    @SuppressWarnings("unchecked")
    @Test
    void i1a_delegateCannotModifyBuiltInRolePermissions() {
        Fixture f = buildFixture();

        // Get the built-in PARTNER_ADMIN role ID
        ResponseEntity<Map> rolesResp = restTemplate.exchange(
            "/baas/v1/roles", HttpMethod.GET,
            new HttpEntity<>(bearer(f.adminJwt())), Map.class);
        String adminRoleId = ((List<Map<String, Object>>) rolesResp.getBody().get("data"))
            .stream()
            .filter(r -> "PARTNER_ADMIN".equals(r.get("name")))
            .findFirst().orElseThrow()
            .get("id").toString();

        // Get any available permission ID
        ResponseEntity<Map> permsResp = restTemplate.exchange(
            "/baas/v1/roles/permissions", HttpMethod.GET,
            new HttpEntity<>(bearer(f.delegateJwt())), Map.class);
        String anyPermId = ((List<Map<String, Object>>) permsResp.getBody().get("data"))
            .get(0).get("id").toString();

        // Delegate tries to PUT permissions on built-in PARTNER_ADMIN role → must be 409
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/roles/" + adminRoleId + "/permissions", HttpMethod.PUT,
            new HttpEntity<>(Map.of("permissionIds", List.of(anyPermId)),
                bearer(f.delegateJwt())),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Editing built-in role permissions must return 409 CONFLICT")
            .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * I1b — delegate tries to grant CREATE_ACCOUNT (which the delegate does NOT hold)
     * to a custom non-built-in role → 403.
     */
    @Test
    void i1b_delegateCannotGrantPermissionItLacks() {
        Fixture f = buildFixture();

        ResponseEntity<Map> resp = restTemplate.exchange(
            "/baas/v1/roles/" + f.customRoleId() + "/permissions", HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("permissionIds", List.of(f.absentPermId().toString())),
                bearer(f.delegateJwt())),
            Map.class);

        assertThat(resp.getStatusCode())
            .as("Granting a permission the caller does not hold must be rejected with 403")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
