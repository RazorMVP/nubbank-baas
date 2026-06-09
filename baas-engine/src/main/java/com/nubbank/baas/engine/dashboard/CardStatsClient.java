package com.nubbank.baas.engine.dashboard;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Engine→card "cards issued" count for the dashboard. Calls card's
 * {@code POST /internal/v1/stats} over the HMAC seam ({@code internalServiceRestTemplate}).
 *
 * <p>BEST-EFFORT: any transport or shape problem returns {@code null} so the dashboard renders
 * every other tile even when card-service is down. Cards live in a separate service/schema, so
 * an outage there must never blank the whole operations console.
 */
@Component
public class CardStatsClient {

    private final RestTemplate http;
    private final String cardBaseUrl;

    public CardStatsClient(
            @Qualifier("internalServiceRestTemplate") RestTemplate http,
            @Value("${app.internal-service.card-base-url}") String cardBaseUrl) {
        this.http = http;
        this.cardBaseUrl = cardBaseUrl;
    }

    /** Cards issued in the partner schema, or {@code null} if card-service is unreachable. */
    public Long cardsIssued(String partnerId, String schemaName) {
        try {
            ResponseEntity<Map> resp = http.postForEntity(
                cardBaseUrl + "/internal/v1/stats",
                new HttpEntity<>(Map.of("partnerId", partnerId, "schemaName", schemaName)),
                Map.class);
            Object data = resp.getBody() == null ? null : resp.getBody().get("data");
            if (data instanceof Map<?, ?> m && m.get("cardsIssued") instanceof Number n) {
                return n.longValue();
            }
            return null;
        } catch (RestClientException unreachable) {
            return null;
        }
    }
}
