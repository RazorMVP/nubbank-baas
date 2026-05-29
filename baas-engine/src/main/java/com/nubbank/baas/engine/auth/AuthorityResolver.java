package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.role.PermissionRepository;
import com.nubbank.baas.engine.role.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Resolves Spring authorities (permission codes) for the active tenant.
 * Queries run inside the current PartnerContext, so Hibernate routes them to the tenant schema.
 */
@Service
@RequiredArgsConstructor
public class AuthorityResolver {

    private final UserRoleRepository userRoleRepo;
    private final PermissionRepository permissionRepo;

    /** Keycloak operator: only the permission codes granted via user_roles. */
    @Transactional(readOnly = true)
    public List<String> operatorAuthorities(UUID userId) {
        return userRoleRepo.findPermissionCodesByUserId(userId);
    }

    /** First-party partner credential (API key / HMAC login): full authority over its own tenant. */
    @Transactional(readOnly = true)
    public List<String> fullTenantAuthorities() {
        return permissionRepo.findAllCodes();
    }
}
