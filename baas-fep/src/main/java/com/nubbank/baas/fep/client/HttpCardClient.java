package com.nubbank.baas.fep.client;

import com.nubbank.baas.fep.common.ApiResponse;
import com.nubbank.baas.fep.config.FepProperties;
import com.nubbank.baas.fep.routing.AuthorizationDecision;
import com.nubbank.baas.fep.routing.CardClient;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.routing.ReversalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Production {@link CardClient} that calls baas-card over HMAC-signed REST.
 *
 * <h2>Fail-closed / fail-safe contract</h2>
 * <ul>
 *   <li>{@link #lookupBin}: returns {@link Optional#empty()} on HTTP 404
 *       <em>and</em> on any {@link RestClientException} (network timeout, DNS failure,
 *       connection refused, etc.).  An unknown or unreachable BIN must NEVER throw
 *       into a Netty pipeline thread.</li>
 *   <li>{@link #authorize}: returns a RC-96 DECLINE on any transport error.
 *       Downstream handlers (Task 6) map RC-96 to "issuer unavailable" for the
 *       ISO 8583 response.</li>
 * </ul>
 *
 * <h2>PAN handling</h2>
 * <p><strong>Never log {@code pan} or any full PAN.</strong>  Log only the BIN,
 * partnerId, amount, and currency for diagnostics.
 */
@Component
public class HttpCardClient implements CardClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCardClient.class);

    /** Fail-safe response returned when the Card service is unreachable or returns an error. */
    private static final AuthorizationDecision SYSTEM_ERROR_DECLINE =
        new AuthorizationDecision("DECLINE", "96", "card service unavailable");

    private static final ReversalDecision REVERSAL_NOT_LOCATED = new ReversalDecision(false);

    private final RestTemplate restTemplate;
    private final String       baseUrl;

    public HttpCardClient(
        @Qualifier("cardRestTemplate") RestTemplate restTemplate,
        FepProperties fepProperties
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl      = fepProperties.card().baseUrl();
    }

    // ──────────────────────────── CardClient ───────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>GETs {@code {baseUrl}/internal/v1/bins/{bin}}.  The {@code bin} parameter
     * is a normalized 8-char BIN (digits only — no PAN data).
     */
    @Override
    public Optional<PartnerRoute> lookupBin(String bin) {
        String url = baseUrl + "/internal/v1/bins/" + bin;
        try {
            ResponseEntity<ApiResponse<PartnerRoute>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<ApiResponse<PartnerRoute>>() {}
            );
            ApiResponse<PartnerRoute> body = response.getBody();
            if (body != null && body.data() != null) {
                log.debug("BIN {} resolved to partnerId={}", bin, body.data().partnerId());
                return Optional.of(body.data());
            }
            log.debug("BIN {} returned 2xx but empty data", bin);
            return Optional.empty();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("BIN {} not found in Card service", bin);
            return Optional.empty();
        } catch (RestClientException e) {
            // Fail-closed: unknown/unreachable BIN must not throw into Netty thread.
            log.warn("BIN lookup for {} failed — Card service error: {}", bin, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>POSTs to {@code {baseUrl}/internal/v1/authorize}.
     *
     * <p><strong>SECURITY:</strong> {@code req.pan()} is NEVER logged.
     * Only {@code partnerId}, {@code amountMinor}, and {@code currency} are safe to log.
     */
    @Override
    public AuthorizationDecision authorize(AuthorizationDecision.Request req) {
        // Log diagnostics WITHOUT the PAN.
        log.debug("Authorizing partnerId={} amount={} currency={}",
            req.partnerId(), req.amountMinor(), req.currency());

        String url = baseUrl + "/internal/v1/authorize";
        try {
            ResponseEntity<ApiResponse<AuthorizationDecision>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<AuthorizationDecision>>() {}
            );
            ApiResponse<AuthorizationDecision> body = response.getBody();
            if (body != null && body.data() != null) {
                log.debug("Authorization result for partnerId={}: decision={} rc={}",
                    req.partnerId(), body.data().decision(), body.data().responseCode());
                return body.data();
            }
            log.warn("Card authorize returned 2xx but empty data for partnerId={}", req.partnerId());
            return SYSTEM_ERROR_DECLINE;
        } catch (RestClientException e) {
            // Fail-safe: transport errors must not propagate into the Netty thread.
            log.warn("Authorize failed for partnerId={} — Card service error: {}",
                req.partnerId(), e.getMessage());
            return SYSTEM_ERROR_DECLINE;
        }
    }

    @Override
    public ReversalDecision reverse(ReversalDecision.Request req) {
        log.debug("Reversing partnerId={} originalStan={} terminal={}",
            req.partnerId(), req.originalStan(), req.terminalId());
        String url = baseUrl + "/internal/v1/reversal";
        try {
            ResponseEntity<ApiResponse<ReversalDecision>> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<ReversalDecision>>() {});
            ApiResponse<ReversalDecision> body = response.getBody();
            if (body != null && body.data() != null) {
                return body.data();
            }
            log.warn("Card reversal returned 2xx but empty data for partnerId={}", req.partnerId());
            return REVERSAL_NOT_LOCATED;
        } catch (RestClientException e) {
            log.warn("Reversal failed for partnerId={} — Card service error: {}",
                req.partnerId(), e.getMessage());
            return REVERSAL_NOT_LOCATED;
        }
    }
}
