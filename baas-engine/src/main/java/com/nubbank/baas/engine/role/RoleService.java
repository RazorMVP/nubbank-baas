package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.role.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepo;
    private final PermissionRepository permRepo;
    private final UserRoleRepository userRoleRepo;

    // ── caller identity helpers ──────────────────────────────────────────────

    private UUID callerUserId() {
        return UUID.fromString(
            (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    private Set<String> callerAuthorities() {
        return SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(Collectors.toSet());
    }

    private boolean callerIsSuperuser() {
        return userRoleRepo.existsSuperuserRoleByUserId(callerUserId());
    }

    // ── service methods ──────────────────────────────────────────────────────

    @Transactional
    public Role createRole(RoleRequest req) {
        requireContext();
        // FIX M3 — reject reserved built-in role names
        if (Set.of(PartnerRoles.ADMIN, PartnerRoles.MAKER, PartnerRoles.APPROVER, PartnerRoles.VIEWER)
                .contains(req.name()))
            throw BaasException.conflict("RESERVED_ROLE_NAME", "Role name is reserved");
        Role r = Role.builder().name(req.name()).description(req.description())
            .builtIn(false).roleScope(PartnerRoles.SCOPE_PARTNER).superuser(false).build();
        return roleRepo.save(r);
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles() {
        requireContext();
        return roleRepo.findByRoleScopeIn(List.of(PartnerRoles.SCOPE_PARTNER, PartnerRoles.SCOPE_SHARED));
    }

    @Transactional
    public Role updateRole(UUID id, RoleRequest req) {
        requireContext();
        Role r = roleRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));
        if (r.isBuiltIn()) throw BaasException.conflict("BUILT_IN_ROLE", "Built-in roles cannot be edited");
        r.setName(req.name()); r.setDescription(req.description());
        return roleRepo.save(r);
    }

    @Transactional
    public Role updatePermissions(UUID roleId, UpdatePermissionsRequest req) {
        requireContext();
        Role role = roleRepo.findById(roleId)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));

        // FIX I1a — built-in roles cannot have their permission set replaced
        if (role.isBuiltIn())
            throw BaasException.conflict("BUILT_IN_ROLE", "Built-in roles cannot be modified");

        Set<Permission> newPermissions = permRepo.findByIdIn(req.permissionIds());

        // FIX I1b — a non-superuser caller cannot grant permissions they do not hold
        if (!callerIsSuperuser()) {
            Set<String> mine = callerAuthorities();
            for (Permission p : newPermissions)
                if (!mine.contains(p.getCode()))
                    throw BaasException.forbidden("PRIVILEGE_ESCALATION",
                        "Cannot grant a permission you do not hold: " + p.getCode());
        }

        // Replace-all pattern
        role.getPermissions().clear();
        role.getPermissions().addAll(newPermissions);
        return roleRepo.save(role);
    }

    @Transactional(readOnly = true)
    public List<Permission> listAllPermissions() {
        requireContext();
        return permRepo.findAll();
    }

    @Transactional
    public void assignUserRole(UUID userId, UUID roleId) {
        requireContext();
        Role role = roleRepo.findById(roleId)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));
        userRoleRepo.save(UserRole.builder().userId(userId).role(role).build());
    }

    @Transactional
    public void deleteRole(UUID id) {
        requireContext();
        Role r = roleRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));
        if (r.isBuiltIn()) throw BaasException.conflict("BUILT_IN_ROLE", "Built-in roles cannot be deleted");
        if (userRoleRepo.existsByRoleId(id))
            throw BaasException.conflict("ROLE_IN_USE", "Role is assigned to one or more users; unassign it first");
        roleRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
