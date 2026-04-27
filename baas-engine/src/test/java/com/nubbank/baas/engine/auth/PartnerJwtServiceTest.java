package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PartnerJwtServiceTest {

    private PartnerJwtService jwtService;
    private final String secret = "test-secret-key-that-is-at-least-32-characters-long-for-hmac";

    @BeforeEach
    void setUp() {
        jwtService = new PartnerJwtService(secret, 24L);
    }

    @Test
    void issue_createsValidToken() {
        String token = jwtService.issue(
            UUID.randomUUID().toString(), "dev@credpal.com", "PARTNER_TELLER",
            UUID.randomUUID().toString(), "Credpal Fintech",
            "partner_abc123", "PRO", "PRODUCTION");
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // JWT = header.payload.signature
    }

    @Test
    void validate_validToken_returnsCorrectContext() {
        String orgId = UUID.randomUUID().toString();
        String token = jwtService.issue(
            UUID.randomUUID().toString(), "dev@credpal.com", "PARTNER_ADMIN",
            orgId, "Credpal", "partner_abc123", "PRO", "PRODUCTION");

        PartnerContext ctx = jwtService.validate(token);
        assertThat(ctx.partnerId()).isEqualTo(orgId);
        assertThat(ctx.schemaName()).isEqualTo("partner_abc123");
        assertThat(ctx.tier()).isEqualTo("PRO");
        assertThat(ctx.environment()).isEqualTo("PRODUCTION");
        assertThat(ctx.authMode()).isEqualTo("JWT");
    }

    @Test
    void validate_tamperedToken_throwsBaasException() {
        String token = jwtService.issue(
            UUID.randomUUID().toString(), "e@e.com", "PARTNER_ADMIN",
            UUID.randomUUID().toString(), "Org", "partner_xyz", "BASIC", "SANDBOX") + "tampered";

        assertThatThrownBy(() -> jwtService.validate(token))
            .isInstanceOf(BaasException.class)
            .hasMessageContaining("Invalid");
    }

    @Test
    void validate_expiredToken_throwsBaasException() {
        // Create a service with 0-hour expiry (expires immediately)
        PartnerJwtService expiredService = new PartnerJwtService(secret, 0L);
        String token = expiredService.issue(
            UUID.randomUUID().toString(), "e@e.com", "PARTNER_ADMIN",
            UUID.randomUUID().toString(), "Org", "partner_xyz", "BASIC", "SANDBOX");

        assertThatThrownBy(() -> jwtService.validate(token))
            .isInstanceOf(BaasException.class);
    }
}
