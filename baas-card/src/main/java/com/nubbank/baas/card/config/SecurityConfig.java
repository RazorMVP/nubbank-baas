package com.nubbank.baas.card.config;

import com.nubbank.baas.card.tenant.PartnerContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final PartnerContextFilter partnerContextFilter;
    private final AuthEnforcementFilter authEnforcementFilter;
    private final InternalServiceAuthFilter internalServiceAuthFilter;

    /**
     * Internal service-to-service chain. Matched FIRST (@Order(1)) so the partner
     * chain at @Order(2) never intercepts {@code /internal/v1/**}. HMAC validation
     * is performed by the InternalServiceAuthFilter; the chain itself permits all
     * requests (the filter is the gate). The partner filters are intentionally NOT
     * added on this path.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/internal/v1/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(internalServiceAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Partner API chain (@Order(2)). Accepts partner JWT (HMAC) + API key only.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/baas/v1/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Filter order:
            // 1. PartnerContextFilter resolves JWT/ApiKey into PartnerContext
            // 2. AuthEnforcementFilter rejects with 401 if context is null on /baas/v1/**
            .addFilterBefore(partnerContextFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(authEnforcementFilter, PartnerContextFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/baas/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                // AuthEnforcementFilter handles 401 for the rest. Spring Security
                // permits everything here so our filter can produce a JSON error
                // envelope consistent with the rest of the API.
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
