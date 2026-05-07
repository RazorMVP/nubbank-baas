package com.nubbank.baas.ncube.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final InternalServiceAuthFilter internalServiceAuthFilter;
    private final AuthEnforcementFilter authEnforcementFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 1. InternalServiceAuthFilter validates HMAC signature, sets NcubeRequestContext
            // 2. AuthEnforcementFilter rejects with 401 if context is null on /baas/v1/**
            .addFilterBefore(internalServiceAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(authEnforcementFilter, InternalServiceAuthFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().permitAll()  // AuthEnforcementFilter handles 401 envelope
            );
        return http.build();
    }

    /**
     * Spring Boot auto-registers any {@link jakarta.servlet.Filter} bean with the servlet container
     * (URL pattern {@code /*}), in addition to the explicit security-chain wiring above. That would
     * cause the filter to run on paths the security chain skips (e.g. {@code /actuator/health}).
     * Disabling auto-registration here keeps the filter bean available for the security chain only.
     */
    @Bean
    public FilterRegistrationBean<InternalServiceAuthFilter> disableInternalServiceAuthFilterAutoRegistration(
            InternalServiceAuthFilter filter) {
        FilterRegistrationBean<InternalServiceAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<AuthEnforcementFilter> disableAuthEnforcementFilterAutoRegistration(
            AuthEnforcementFilter filter) {
        FilterRegistrationBean<AuthEnforcementFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
