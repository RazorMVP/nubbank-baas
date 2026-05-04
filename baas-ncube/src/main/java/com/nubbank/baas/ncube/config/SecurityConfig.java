package com.nubbank.baas.ncube.config;

import lombok.RequiredArgsConstructor;
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
}
