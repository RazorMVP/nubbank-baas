package com.nubbank.baas.engine.config;

import com.nubbank.baas.engine.tenant.PartnerContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    private final RateLimitFilter rateLimitFilter;
    private final AuthEnforcementFilter authEnforcementFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Filter order:
            // 1. PartnerContextFilter resolves JWT/ApiKey into PartnerContext
            // 2. AuthEnforcementFilter rejects with 401 if context is null on /baas/v1/**
            // 3. RateLimitFilter applies tier-based rate limits using the resolved context
            .addFilterBefore(partnerContextFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(authEnforcementFilter, PartnerContextFilter.class)
            .addFilterAfter(rateLimitFilter, AuthEnforcementFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/baas/v1/auth/**").permitAll()
                .requestMatchers("/baas/v1/partners/register").permitAll()
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
