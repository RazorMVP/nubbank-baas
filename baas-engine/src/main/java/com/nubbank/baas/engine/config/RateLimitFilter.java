package com.nubbank.baas.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.tenant.PartnerContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    // Optional injection — RateLimitService is @ConditionalOnBean(StringRedisTemplate.class)
    // so it won't exist when Redis autoconfiguration is excluded (e.g. in tests).
    // Using Optional<> here means the filter is always registered but simply skips
    // rate limiting when Redis is not available — fail-open behaviour.
    private final Optional<RateLimitService> rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        PartnerContext ctx = PartnerContext.get();
        if (ctx != null && rateLimitService.isPresent()) {
            try {
                var result = rateLimitService.get().check(ctx.partnerId(), ctx.tier(), ctx.environment());

                response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
                response.setHeader("X-RateLimit-Remaining",
                    String.valueOf(Math.max(0, result.limit() - result.current())));
                response.setHeader("X-RateLimit-Reset",
                    String.valueOf(System.currentTimeMillis() / 1000L + result.resetInSeconds()));

                if (!result.allowed()) {
                    response.setStatus(429);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setHeader("Retry-After", "60");
                    response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.error("RATE_LIMIT_EXCEEDED",
                            "API rate limit exceeded. Retry after 60 seconds.")));
                    return;
                }
            } catch (Exception ex) {
                // Redis unavailable — fail open
                log.debug("Rate limit check failed, allowing request: {}", ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/baas/v1/auth/") || path.startsWith("/actuator/");
    }
}
