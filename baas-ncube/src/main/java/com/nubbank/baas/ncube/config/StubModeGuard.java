package com.nubbank.baas.ncube.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * Refuses to start when baas.nps.live=false AND SPRING_PROFILES_ACTIVE contains 'prod'.
 *
 * <p>Stub mode in production is forbidden — silent stub responses to real customers
 * would be a CBN audit failure. Operators get fail-fast at boot, never at first
 * customer-impacting request.
 *
 * <p>In stub mode without prod profile (e.g. dev/local), logs a startup banner
 * to make the configuration visible to operators tailing logs.
 */
@Component
@Slf4j
public class StubModeGuard {

    private final Environment env;
    private final boolean live;

    public StubModeGuard(Environment env, @Value("${baas.nps.live:false}") boolean live) {
        this.env = env;
        this.live = live;
    }

    @PostConstruct
    public void onStartup() {
        // Case-insensitive prefix match: catches "prod", "PROD", "Prod", "production",
        // "prod-eu", etc. The guard's job is preventing ops slips, so the matcher errs
        // on the side of permissive — anything that *might* be production trips the gate.
        boolean prod = Arrays.stream(env.getActiveProfiles())
            .anyMatch(p -> p != null && p.toLowerCase(Locale.ROOT).startsWith("prod"));
        if (!live && prod) {
            throw new IllegalStateException(
                "FATAL: baas-ncube is in stub mode (baas.nps.live=false) but SPRING_PROFILES_ACTIVE includes 'prod'. "
                + "Stub mode in production is forbidden — set NPS_LIVE=true or remove the prod profile.");
        }
        if (!live) {
            log.warn("");
            log.warn("╔═════════════════════════════════════════════════════════════════════╗");
            log.warn("║  STUB MODE: NIBSS calls are mocked. baas-ncube returns stub data.   ║");
            log.warn("║  DO NOT deploy with this configuration to production.               ║");
            log.warn("║  All stubbed responses include header X-NubBank-Stubbed: true.      ║");
            log.warn("╚═════════════════════════════════════════════════════════════════════╝");
            log.warn("");
        }
    }
}
