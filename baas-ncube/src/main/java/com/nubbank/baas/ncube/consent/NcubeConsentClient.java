package com.nubbank.baas.ncube.consent;

import com.nubbank.baas.ncube.common.NcubeException;
import com.nubbank.baas.ncube.consent.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Slf4j
@Service
public class NcubeConsentClient {

    private final RestTemplate restTemplate;
    private final String baasEngineBaseUrl;

    public NcubeConsentClient(@Qualifier("baasEngineRestTemplate") RestTemplate restTemplate,
                               String baasEngineBaseUrl) {
        this.restTemplate = restTemplate;
        this.baasEngineBaseUrl = baasEngineBaseUrl;
    }

    public List<NubBankConsentDto> getConsents(String authHeader) {
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/open-banking/consents",
                HttpMethod.GET, withAuth(authHeader), Map.class);
            return extractConsents(resp.getBody());
        } catch (RestClientException ex) {
            log.warn("getConsents failed: {}", ex.getMessage());
            return List.of();
        }
    }

    public NubBankConsentDto createConsent(String authHeader, CbnConsentRequest req) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("scopes", req.Data().Permissions());
            if (req.Data().ExpirationDateTime() != null) {
                body.put("expiryDate", req.Data().ExpirationDateTime());
            }
            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/open-banking/consents",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            return extractSingle(resp.getBody());
        } catch (RestClientException ex) {
            throw new NcubeException("CONSENT_CREATE_FAILED",
                "Failed to create consent: " + ex.getMessage());
        }
    }

    public void revokeConsent(String authHeader, String consentId) {
        try {
            restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/open-banking/consents/" + consentId,
                HttpMethod.DELETE, withAuth(authHeader), Void.class);
        } catch (RestClientException ex) {
            log.warn("revokeConsent {} failed: {}", consentId, ex.getMessage());
        }
    }

    private HttpEntity<Void> withAuth(String h) {
        HttpHeaders headers = new HttpHeaders();
        if (h != null) headers.set("Authorization", h);
        return new HttpEntity<>(headers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<NubBankConsentDto> extractConsents(Map body) {
        if (body == null) return List.of();
        Object data = body.get("data");
        if (data instanceof List<?> l) {
            return ((List<Map>) (List<?>) l).stream().map(this::toDto).toList();
        }
        return List.of();
    }

    private NubBankConsentDto extractSingle(Map body) {
        if (body == null) throw new NcubeException("INVALID_RESPONSE", "Empty response");
        Object data = body.get("data");
        if (data instanceof Map m) return toDto(m);
        throw new NcubeException("INVALID_RESPONSE", "Unexpected format");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private NubBankConsentDto toDto(Map m) {
        Object s = m.get("scopes");
        List<String> scopes = s instanceof List
            ? ((List<?>) s).stream().map(Object::toString).toList()
            : List.of();
        return new NubBankConsentDto(
            str(m, "id"), str(m, "status"), scopes,
            str(m, "tppClientId"), str(m, "expiryDate"), str(m, "createdAt"));
    }

    @SuppressWarnings("rawtypes")
    private String str(Map m, String k) {
        return String.valueOf(m.getOrDefault(k, ""));
    }
}
