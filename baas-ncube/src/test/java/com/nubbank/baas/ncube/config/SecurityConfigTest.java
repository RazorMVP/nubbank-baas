package com.nubbank.baas.ncube.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the documented filter chain ordering:
 * InternalServiceAuthFilter (sets context) MUST run before AuthEnforcementFilter (rejects null context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigTest {

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    void internal_service_auth_filter_runs_before_auth_enforcement_filter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/baas/v1/ncube/identity/verify-bvn");
        request.setMethod("POST");

        // Locate the SecurityFilterChain that matches our request, then read its public filter list.
        // FilterChainProxy.getFilters(HttpServletRequest) is package-private, so we resolve the
        // matching chain ourselves via the public getFilterChains() API.
        SecurityFilterChain matched = filterChainProxy.getFilterChains().stream()
            .filter(chain -> chain.matches(request))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No SecurityFilterChain matched request " + request.getRequestURI()));

        List<Filter> filters = matched.getFilters();
        List<String> names = filters.stream().map(f -> f.getClass().getSimpleName()).toList();

        int internalIdx = names.indexOf("InternalServiceAuthFilter");
        int enforceIdx = names.indexOf("AuthEnforcementFilter");

        assertThat(internalIdx)
            .as("InternalServiceAuthFilter must be present in the security chain. Found: %s", names)
            .isNotNegative();
        assertThat(enforceIdx)
            .as("AuthEnforcementFilter must be present in the security chain. Found: %s", names)
            .isNotNegative();
        assertThat(enforceIdx)
            .as("AuthEnforcementFilter must run AFTER InternalServiceAuthFilter. Found: %s", names)
            .isGreaterThan(internalIdx);
    }
}
