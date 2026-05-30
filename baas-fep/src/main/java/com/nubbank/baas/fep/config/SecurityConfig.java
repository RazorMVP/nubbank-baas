package com.nubbank.baas.fep.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * FEP security configuration.
 *
 * The FEP exposes no partner-facing HTTP API. The ISO 8583 TCP server
 * (Task 3) is the primary inbound surface and is not behind Spring Security.
 * The only HTTP surface is the Spring Boot Actuator (health probes, info).
 *
 * Policy: permit /actuator/health/** for liveness/readiness probes;
 * deny everything else — including any accidentally-added endpoints.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().denyAll()
            );
        return http.build();
    }
}
