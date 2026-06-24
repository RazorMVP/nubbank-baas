package com.nubbank.baas.engine.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.partner.PartnerApiKeyRepository;
import com.nubbank.baas.engine.role.PermissionRepository;
import com.nubbank.baas.engine.role.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;
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
    private final PartnerApiKeyRepository apiKeyRepo;
    private final ObjectMapper objectMapper;

    /** Keycloak operator: superuser → full authority; otherwise only the granted permission codes. */
    @Transactional(readOnly = true)
    public List<String> operatorAuthorities(UUID userId) {
        if (userRoleRepo.existsSuperuserRoleByUserId(userId)) {
            return permissionRepo.findAllCodes();
        }
        return userRoleRepo.findPermissionCodesByUserId(userId);
    }

    /** Partner user: PARTNER_ADMIN (superuser) → dynamic full; otherwise union of assigned roles' codes. */
    @Transactional(readOnly = true)
    public List<String> partnerUserAuthorities(UUID partnerUserId) {
        if (userRoleRepo.existsSuperuserRoleByUserId(partnerUserId)) {
            return permissionRepo.findAllCodes();
        }
        return userRoleRepo.findPermissionCodesByUserId(partnerUserId);
    }

    /** API key: scopes ["*"] → dynamic full; explicit codes → those codes; [] → deny (empty). */
    @Transactional(readOnly = true)
    public List<String> apiKeyAuthorities(UUID apiKeyId) {
        return apiKeyRepo.findById(apiKeyId).map(k -> {
            List<String> scopes;
            try {
                scopes = objectMapper.readValue(
                    k.getScopes() == null ? "[]" : k.getScopes(),
                    new TypeReference<List<String>>() {});
            } catch (Exception ex) {
                return Collections.<String>emptyList();
            }
            if (scopes.contains("*")) return permissionRepo.findAllCodes();
            return scopes;
        }).orElseGet(Collections::emptyList);
    }
}
