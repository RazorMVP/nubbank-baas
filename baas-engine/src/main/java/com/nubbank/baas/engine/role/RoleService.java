package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.role.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepo;
    private final PermissionRepository permRepo;
    private final UserRoleRepository userRoleRepo;

    @Transactional
    public Role createRole(RoleRequest req) {
        requireContext();
        return roleRepo.save(Role.builder().name(req.name()).description(req.description()).build());
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles() { requireContext(); return roleRepo.findAll(); }

    @Transactional
    public Role updateRole(UUID id, RoleRequest req) {
        requireContext();
        Role role = roleRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));
        role.setName(req.name()); role.setDescription(req.description());
        return roleRepo.save(role);
    }

    @Transactional
    public Role updatePermissions(UUID roleId, UpdatePermissionsRequest req) {
        requireContext();
        Role role = roleRepo.findById(roleId)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));
        Set<Permission> resolved = permRepo.findByIdIn(req.permissionIds());
        // Replace-all pattern
        role.getPermissions().clear();
        role.getPermissions().addAll(resolved);
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
        Role role = roleRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role not found"));
        role.setDisabled(true);
        roleRepo.save(role);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
