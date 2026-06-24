package com.nubbank.baas.engine.partner.user;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.user.dto.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartnerUserService {

    private final PartnerUserRepository userRepo;
    private final PartnerOrganizationRepository orgRepo;
    private final RoleRepository roleRepo;
    private final UserRoleRepository userRoleRepo;
    private final BCryptPasswordEncoder encoder;

    private UUID callerOrgId() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) throw BaasException.unauthorized("NO_CONTEXT", "No partner context");
        return UUID.fromString(ctx.partnerId());
    }

    private UUID callerUserId() {
        return UUID.fromString(
            (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    private Set<String> callerAuthorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(Collectors.toSet());
    }

    private PartnerOrganization orgRef() {
        return orgRepo.findById(callerOrgId())
            .orElseThrow(() -> BaasException.notFound("ORG_NOT_FOUND", "Org not found"));
    }

    /**
     * Roles must be PARTNER/SHARED-scoped and grant nothing the caller lacks (no escalation).
     */
    private List<Role> resolveAssignableRoles(Set<UUID> roleIds) {
        boolean superuser = userRoleRepo.existsSuperuserRoleByUserId(callerUserId());
        Set<String> mine = callerAuthorities();
        List<Role> roles = new ArrayList<>();
        for (UUID id : roleIds) {
            Role r = roleRepo.findById(id)
                .filter(x -> List.of(PartnerRoles.SCOPE_PARTNER, PartnerRoles.SCOPE_SHARED)
                    .contains(x.getRoleScope()))
                .orElseThrow(() -> BaasException.notFound("ROLE_NOT_FOUND", "Role " + id + " not found"));
            if (!superuser) {
                if (r.isSuperuser())
                    throw BaasException.forbidden("PRIVILEGE_ESCALATION",
                        "Cannot assign a superuser role");
                Set<String> roleCodes = r.getPermissions().stream()
                    .map(Permission::getCode).collect(Collectors.toSet());
                if (!mine.containsAll(roleCodes))
                    throw BaasException.forbidden("PRIVILEGE_ESCALATION",
                        "Cannot grant a role exceeding your own authority");
            }
            roles.add(r);
        }
        return roles;
    }

    @Transactional
    public PartnerUser create(CreatePartnerUserRequest req) {
        if (userRepo.existsByEmail(req.email()))
            throw BaasException.conflict("EMAIL_TAKEN", "Email already exists");
        List<Role> roles = resolveAssignableRoles(req.roleIds());
        PartnerUser u = userRepo.save(PartnerUser.builder()
            .organization(orgRef())
            .email(req.email())
            .passwordHash(encoder.encode(req.password()))
            .role("PARTNER_USER")
            .active(true)
            .build());
        roles.forEach(r -> userRoleRepo.save(UserRole.builder().userId(u.getId()).role(r).build()));
        return u;
    }

    @Transactional(readOnly = true)
    public List<PartnerUserResponse> list() {
        return userRepo.findByOrganization_Id(callerOrgId()).stream()
            .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PartnerUserResponse get(UUID id) {
        return toResponse(requireOwnOrg(id));
    }

    @Transactional
    public PartnerUserResponse replaceRoles(UUID id, UpdateUserRolesRequest req) {
        requireOwnOrg(id);
        List<Role> roles = resolveAssignableRoles(req.roleIds());
        boolean currentlyAdmin = holdsAdmin(id);
        boolean willBeAdmin = roles.stream().anyMatch(r -> r.getName().equals(PartnerRoles.ADMIN));
        boolean targetActive = userRepo.findById(id).map(PartnerUser::isActive).orElse(false);
        if (currentlyAdmin && !willBeAdmin && targetActive && activeAdminCount() <= 1)
            throw BaasException.conflict("LAST_ADMIN", "Cannot remove the last active PARTNER_ADMIN");
        userRoleRepo.deleteByUserId(id);
        roles.forEach(r -> userRoleRepo.save(UserRole.builder().userId(id).role(r).build()));
        return toResponse(requireOwnOrg(id));
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        PartnerUser u = requireOwnOrg(id);
        if (!active && u.isActive() && holdsAdmin(id) && activeAdminCount() <= 1)
            throw BaasException.conflict("LAST_ADMIN", "Cannot deactivate the last active PARTNER_ADMIN");
        u.setActive(active);
        userRepo.save(u);
    }

    /** Number of ACTIVE users holding PARTNER_ADMIN in the current tenant. */
    private long activeAdminCount() {
        List<UUID> ids = userRoleRepo.findUserIdsByRoleName(PartnerRoles.ADMIN);
        return ids.isEmpty() ? 0 : userRepo.countByIdInAndActiveTrue(ids);
    }

    private boolean holdsAdmin(UUID userId) {
        return userRoleRepo.findRoleNamesByUserId(userId).contains(PartnerRoles.ADMIN);
    }

    private PartnerUser requireOwnOrg(UUID id) {
        return userRepo.findById(id)
            .filter(x -> x.getOrganization().getId().equals(callerOrgId()))
            .orElseThrow(() -> BaasException.notFound("USER_NOT_FOUND", "User " + id + " not found"));
    }

    private PartnerUserResponse toResponse(PartnerUser u) {
        return new PartnerUserResponse(
            u.getId(), u.getEmail(), u.isActive(),
            userRoleRepo.findRoleNamesByUserId(u.getId()));
    }
}
