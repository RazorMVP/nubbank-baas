package com.nubbank.baas.card.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.card.engine.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Outbound client to baas-engine over the HMAC seam (Stage 5).
 *
 * <p>FAIL-CLOSED: any transport error (DNS, refused, timeout, non-2xx) maps to a safe
 * sentinel — debit → {@code "UNREACHABLE"} (the card maps this to RC 91); credit/lookup →
 * {@code false}. The authorize/reversal/issuance paths therefore never see an exception.
 *
 * <p>Reads the standard {@code ApiResponse} envelope's {@code data} field and re-binds it into
 * the target record via Jackson.
 */
@Component
public class EngineClient {

    private static final Logger log = LoggerFactory.getLogger(EngineClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate http;
    private final String baseUrl;

    public EngineClient(@Qualifier("internalServiceRestTemplate") RestTemplate http,
                        @Value("${app.internal-service.engine-base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public CardDebitResult cardDebit(CardDebitRequest req) {
        return post("/internal/v1/card-debit", req, CardDebitResult.class, new CardDebitResult("UNREACHABLE"));
    }

    public CardCreditResult cardCredit(CardCreditRequest req) {
        return post("/internal/v1/card-credit", req, CardCreditResult.class, new CardCreditResult(false));
    }

    public AccountLookupResult accountLookup(AccountLookupRequest req) {
        return post("/internal/v1/account-lookup", req, AccountLookupResult.class,
            new AccountLookupResult(false, false, null));
    }

    private <T> T post(String path, Object body, Class<T> type, T failClosed) {
        try {
            ResponseEntity<Map<String, Object>> resp = http.exchange(
                baseUrl + path, HttpMethod.POST, new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return failClosed;
            }
            Object data = resp.getBody().get("data");
            if (data == null) {
                return failClosed;
            }
            return MAPPER.convertValue(data, type);
        } catch (RestClientException ex) {
            log.warn("Engine call {} failed, failing closed: {}", path, ex.getMessage());
            return failClosed;
        }
    }
}
