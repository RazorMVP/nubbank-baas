package com.nubbank.baas.engine.tenant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Engine → card provisioning trigger (DEF-1C-22). Calls card's
 * {@code POST /internal/v1/provision} over the HMAC seam after the engine has run its own
 * tenant migrations, so card schema objects exist in the partner schema.
 *
 * <p>A failure propagates so {@link TenantProvisioningService} can mark the whole
 * provisioning FAILED — a partner is never left half-provisioned. Provisioning is idempotent
 * on both sides, so the whole {@code provision()} can be safely retried.
 */
@Component
public class CardProvisioningClient {

    private final RestTemplate http;
    private final String cardBaseUrl;
    private final boolean enabled;

    public CardProvisioningClient(
            @Qualifier("internalServiceRestTemplate") RestTemplate http,
            @Value("${app.internal-service.card-base-url}") String cardBaseUrl,
            @Value("${app.internal-service.card-provisioning-enabled:true}") boolean enabled) {
        this.http = http;
        this.cardBaseUrl = cardBaseUrl;
        this.enabled = enabled;
    }

    public void provision(UUID partnerId, String schemaName) {
        // Disabled only in the engine test profile, where baas-card is not running. Production
        // keeps this ON so a card-provisioning failure fails the whole provisioning.
        if (!enabled) {
            return;
        }
        http.postForEntity(cardBaseUrl + "/internal/v1/provision",
            new HttpEntity<>(Map.of("partnerId", partnerId.toString(), "schemaName", schemaName)),
            Void.class);
    }
}
