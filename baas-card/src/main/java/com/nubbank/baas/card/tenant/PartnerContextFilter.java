package com.nubbank.baas.card.tenant;

import com.nubbank.baas.card.auth.ApiKeyResolver;
import com.nubbank.baas.card.auth.PartnerJwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/**
 * Resolves partner identity from a first-party credential (API key or partner JWT),
 * sets {@link PartnerContext}, and ALWAYS clears it in a {@code finally} block.
 *
 * Auth scope (DEF-1C-20): baas-card accepts partner JWT (HMAC) + API key only.
 * Any resolved first-party credential is granted full tenant authority
 * (ROLE_PARTNER). Operator-JWT / Keycloak RBAC is DEFERRED — unlike baas-engine
 * there is no issuer peek, no OperatorJwtResolver, and no AuthorityResolver
 * user_roles query.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerContextFilter extends OncePerRequestFilter {

    private final PartnerJwtService jwtService;
    private final ApiKeyResolver apiKeyResolver;

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

    private void resolveContext(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) return;

        if (authHeader.startsWith(API_KEY_PREFIX)) {
            apiKeyResolver.resolve(authHeader.substring(API_KEY_PREFIX.length()).trim());
        } else if (authHeader.startsWith(BEARER_PREFIX)) {
            resolveJwt(authHeader.substring(BEARER_PREFIX.length()).trim());
        }
    }

    private void resolveJwt(String token) {
        // First-party HMAC partner JWT only. No issuer peek / operator branch (deferred).
        try {
            PartnerContext.set(jwtService.validate(token));
        } catch (Exception ex) {
            log.debug("JWT resolution failed: {}", ex.getMessage());
        }
    }

    private void populateAuthorities() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) return;
        // Card grants full tenant authority to any resolved first-party credential.
        var auth = new UsernamePasswordAuthenticationToken(
            ctx.userId() != null ? ctx.userId() : ctx.partnerId(),
            null, List.of(new SimpleGrantedAuthority("ROLE_PARTNER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
