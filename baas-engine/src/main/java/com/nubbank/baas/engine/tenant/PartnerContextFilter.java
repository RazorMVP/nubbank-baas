package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.auth.AuthorityResolver;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.auth.keycloak.OperatorJwtResolver;
import com.nubbank.baas.engine.partner.PartnerApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerContextFilter extends OncePerRequestFilter {

    private final PartnerJwtService jwtService;
    private final PartnerApiKeyRepository apiKeyRepo;
    private final OperatorJwtResolver operatorResolver;
    private final AuthorityResolver authorityResolver;

    private static final String API_KEY_PREFIX = "ApiKey ";
    private static final String BEARER_PREFIX   = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            resolveContext(request);
            populateAuthorities();
            chain.doFilter(request, response);
        } finally {
            PartnerContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void populateAuthorities() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) return;

        List<String> codes;
        if ("OPERATOR_JWT".equals(ctx.authMode())) {
            UUID operatorId;
            try {
                operatorId = UUID.fromString(ctx.userId());
            } catch (IllegalArgumentException ex) {
                // Operator subject is not a UUID — cannot map to user_roles. Fail closed:
                // leave the SecurityContext empty so AuthEnforcementFilter returns 401.
                log.warn("Operator JWT subject is not a valid UUID — denying request");
                return;
            }
            codes = authorityResolver.operatorAuthorities(operatorId);
        } else {
            // First-party credentials (API_KEY, JWT) get full tenant authority.
            // NOTE: any future authMode added here falls through to FULL authority by default —
            // when introducing a new authMode, decide explicitly whether it belongs here or
            // needs its own RBAC-scoped branch (see DEF-1C-15).
            codes = authorityResolver.fullTenantAuthorities();
        }

        List<GrantedAuthority> authorities = codes.stream()
            .map(SimpleGrantedAuthority::new)
            .map(a -> (GrantedAuthority) a)
            .toList();
        String principal = ctx.userId() == null ? ctx.partnerId() : ctx.userId();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private void resolveContext(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) return;

        if (authHeader.startsWith(API_KEY_PREFIX)) {
            resolveApiKey(authHeader.substring(API_KEY_PREFIX.length()).trim());
        } else if (authHeader.startsWith(BEARER_PREFIX)) {
            resolveJwt(authHeader.substring(BEARER_PREFIX.length()).trim());
        }
    }

    private void resolveApiKey(String rawKey) {
        try {
            String keyHash = sha256Hex(rawKey);
            apiKeyRepo.findByKeyHashAndActiveTrue(keyHash).ifPresent(key -> {
                PartnerContext ctx = new PartnerContext(
                    key.getOrganization().getId().toString(),
                    key.getOrganization().getSchemaName(),
                    key.getTier().name(),
                    key.getEnvironment().name(),
                    "API_KEY",
                    null
                );
                PartnerContext.set(ctx);
                // Update last_used_at — the repository method is @Transactional
                apiKeyRepo.updateLastUsed(key.getId(), Instant.now());
            });
        } catch (Exception ex) {
            log.debug("API key resolution failed: {}", ex.getMessage());
            // Do not expose the failure — just leave PartnerContext null
        }
    }

    private void resolveJwt(String token) {
        // Keycloak operator/admin token? Branch on the iss claim.
        String issuer = operatorResolver.peekIssuer(token);
        if (issuer != null && operatorResolver.isAdminIssuer(issuer)) {
            // Admin tokens are not valid on the partner API. Leave context null →
            // AuthEnforcementFilter returns 401 on /baas/v1/**. Admin chain is a later track.
            log.debug("Admin-issuer token presented to partner API — rejected (no partner context)");
            return;
        }
        // Known Keycloak issuer: resolve here and RETURN even on failure — must NOT fall through
        // to the HMAC verifier, or a bad Keycloak token for a known issuer would get a second
        // (wrong) validation path. The return in the catch is load-bearing security, not flow sugar.
        if (issuer != null) {
            try { PartnerContext.set(operatorResolver.resolve(token)); return; }
            catch (Exception ex) { log.debug("Operator JWT resolution failed: {}", ex.getMessage()); return; }
        }
        // Fall back to the existing first-party HMAC partner JWT.
        try { PartnerContext.set(jwtService.validate(token)); }
        catch (Exception ex) { log.debug("JWT resolution failed: {}", ex.getMessage()); }
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
