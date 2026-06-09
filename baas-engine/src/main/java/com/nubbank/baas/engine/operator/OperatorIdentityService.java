package com.nubbank.baas.engine.operator;

import com.nubbank.baas.engine.role.UserRoleRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds the {@link OperatorMeResponse} for the current request from the {@link PartnerContext}
 * (set by the tenant filter) and the Spring {@link Authentication} (whose granted authorities
 * are the per-request resolved permission codes). Roles are looked up from {@code user_roles}
 * only when the subject is a UUID that maps to a row — first-party partner subjects don't.
 */
@Service
@RequiredArgsConstructor
public class OperatorIdentityService {

    private final UserRoleRepository userRoleRepo;

    @Transactional(readOnly = true)
    public OperatorMeResponse me(Authentication authentication) {
        PartnerContext ctx = PartnerContext.get();
        List<String> authorities = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .sorted()
            .toList();
        return new OperatorMeResponse(
            ctx == null ? null : ctx.userId(),
            ctx == null ? null : ctx.authMode(),
            ctx == null ? null : ctx.partnerId(),
            ctx == null ? null : ctx.tier(),
            ctx == null ? null : ctx.environment(),
            resolveRoles(ctx),
            authorities);
    }

    private List<String> resolveRoles(PartnerContext ctx) {
        if (ctx == null || ctx.userId() == null) return List.of();
        UUID userId;
        try {
            userId = UUID.fromString(ctx.userId());
        } catch (IllegalArgumentException notAUuid) {
            return List.of();
        }
        return userRoleRepo.findRoleNamesByUserId(userId);
    }
}
