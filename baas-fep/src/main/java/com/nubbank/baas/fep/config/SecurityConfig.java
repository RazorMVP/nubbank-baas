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
 *
 * This chain is intentionally catch-all (no securityMatcher) so there is no
 * fallthrough to Spring Boot's auto-configured default chain and the
 * UserDetailsServiceAutoConfiguration generated-password warning is suppressed.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain fepChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable())
            .httpBasic(h -> h.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().denyAll()
            );
        return http.build();
    }
}
