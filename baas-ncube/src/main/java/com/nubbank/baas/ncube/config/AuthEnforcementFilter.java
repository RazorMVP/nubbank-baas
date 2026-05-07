package com.nubbank.baas.ncube.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class AuthEnforcementFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (requiresAuth(path) && NcubeRequestContext.get() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"data\":null,\"errors\":[{\"code\":\"MISSING_AUTH\","
                + "\"message\":\"Authorization header required — Internal HMAC token from baas-engine\"}]}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresAuth(String path) {
        if (path == null) return false;
        if (path.startsWith("/actuator/health")) return false;
        if (path.startsWith("/v3/api-docs")) return false;
        if (path.startsWith("/swagger-ui")) return false;
        return path.startsWith("/baas/v1/");
    }
}
