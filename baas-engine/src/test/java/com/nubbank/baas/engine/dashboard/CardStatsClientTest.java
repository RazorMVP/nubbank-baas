package com.nubbank.baas.engine.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The engine→card "cards issued" count is best-effort: a healthy card-service yields the
 * count; an unreachable one yields {@code null} so the dashboard still renders every other
 * tile. This is the graceful-degradation contract the DashboardService relies on.
 */
class CardStatsClientTest {

    @Test
    void cardsIssued_cardServiceHealthy_returnsCount() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForEntity(anyString(), any(), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("cardsIssued", 7))));

        CardStatsClient client = new CardStatsClient(http, "http://baas-card:8081");

        assertThat(client.cardsIssued("partner-1", "partner_abc")).isEqualTo(7L);
    }

    @Test
    void cardsIssued_cardServiceUnreachable_returnsNull() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForEntity(anyString(), any(), eq(Map.class)))
            .thenThrow(new ResourceAccessException("connection refused"));

        CardStatsClient client = new CardStatsClient(http, "http://baas-card:8081");

        assertThat(client.cardsIssued("partner-1", "partner_abc")).isNull();
    }
}
