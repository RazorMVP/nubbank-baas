package com.nubbank.baas.engine.config;

import com.nubbank.baas.engine.tenant.PartnerContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Enforces that every {@code /baas/v1/**} request (except auth and the
 * documented public paths) has a resolved {@link PartnerContext}.
 *
 * Runs AFTER {@link com.nubbank.baas.engine.tenant.PartnerContextFilter}, which
 * sets the context if a valid JWT or API key is present. If the context is
 * still null after that filter, this filter rejects the request with 401.
 *
 * Replaces the previous "every service method must remember to call
 * {@code requireContext()}" pattern, which was easy to forget and silently
 * exposed new endpoints. Security is now a single config gate.
 */
@Component
public class AuthEnforcementFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        if (requiresAuth(path) && PartnerContext.get() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"data\":null,\"errors\":[{\"code\":\"MISSING_AUTH\","
                + "\"message\":\"Authorization header required — use ApiKey or Bearer JWT\"}]}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresAuth(String path) {
        if (path == null) return false;
        // Public paths — no auth required
        if (path.startsWith("/baas/v1/auth/")) return false;
        if (path.startsWith("/baas/v1/partners/register")) return false;
        if (path.startsWith("/actuator/health")) return false;
        if (path.startsWith("/v3/api-docs")) return false;
        if (path.startsWith("/swagger-ui")) return false;
        // Everything under /baas/v1/ that's NOT a public path is protected
        return path.startsWith("/baas/v1/");
    }
}
