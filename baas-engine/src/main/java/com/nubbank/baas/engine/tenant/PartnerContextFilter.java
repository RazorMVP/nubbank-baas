package com.nubbank.baas.engine.tenant;

import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.auth.keycloak.OperatorJwtResolver;
import com.nubbank.baas.engine.partner.PartnerApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerContextFilter extends OncePerRequestFilter {

    private final PartnerJwtService jwtService;
    private final PartnerApiKeyRepository apiKeyRepo;
    private final OperatorJwtResolver operatorResolver;

    private static final String API_KEY_PREFIX = "ApiKey ";
    private static final String BEARER_PREFIX   = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            resolveContext(request);
            chain.doFilter(request, response);
        } finally {
            PartnerContext.clear(); // always clear — prevents ThreadLocal leaks in thread pools
        }
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
