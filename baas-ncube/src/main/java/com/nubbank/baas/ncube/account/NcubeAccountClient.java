package com.nubbank.baas.ncube.account;

import com.nubbank.baas.ncube.account.dto.*;
import com.nubbank.baas.ncube.common.NcubeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
public class NcubeAccountClient {

    private final RestTemplate restTemplate;
    private final String baasEngineBaseUrl;

    public NcubeAccountClient(@Qualifier("baasEngineRestTemplate") RestTemplate restTemplate,
                               String baasEngineBaseUrl) {
        this.restTemplate = restTemplate;
        this.baasEngineBaseUrl = baasEngineBaseUrl;
    }

    public List<NubBankAccountDto> getAccounts(String authHeader) {
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/accounts?page=0&size=100",
                HttpMethod.GET, withAuth(authHeader), Map.class);
            return extractList(resp.getBody()).stream().map(this::toAccountDto).toList();
        } catch (RestClientException ex) {
            log.warn("getAccounts failed: {}", ex.getMessage());
            return List.of();
        }
    }

    public NubBankAccountDto getBalance(String authHeader, String accountNumber) {
        return getAccounts(authHeader).stream()
            .filter(a -> a.accountNumber().equals(accountNumber))
            .findFirst()
            .orElseThrow(() -> new NcubeException("ACCOUNT_NOT_FOUND",
                "Account " + accountNumber + " not found"));
    }

    public List<NubBankTransactionDto> getTransactions(String authHeader, String accountNumber) {
        try {
            String accountId = getAccounts(authHeader).stream()
                .filter(a -> a.accountNumber().equals(accountNumber))
                .map(NubBankAccountDto::id)
                .findFirst()
                .orElseThrow(() -> new NcubeException("ACCOUNT_NOT_FOUND",
                    "Account " + accountNumber + " not found"));
            ResponseEntity<Map> resp = restTemplate.exchange(
                baasEngineBaseUrl + "/baas/v1/accounts/" + accountId + "/transactions?page=0&size=100",
                HttpMethod.GET, withAuth(authHeader), Map.class);
            return extractList(resp.getBody()).stream().map(this::toTxDto).toList();
        } catch (NcubeException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("getTransactions failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private HttpEntity<Void> withAuth(String h) {
        HttpHeaders headers = new HttpHeaders();
        if (h != null) headers.set("Authorization", h);
        return new HttpEntity<>(headers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map> extractList(Map body) {
        if (body == null) return List.of();
        Object data = body.get("data");
        if (data instanceof Map m) {
            Object content = m.get("content");
            if (content instanceof List<?> l) return (List<Map>) (List<?>) l;
        }
        return List.of();
    }

    @SuppressWarnings("rawtypes")
    private NubBankAccountDto toAccountDto(Map m) {
        return new NubBankAccountDto(
            str(m, "id"), str(m, "accountNumber"),
            str(m, "accountTypeLabel"), str(m, "status"),
            decimal(m, "balance"), decimal(m, "availableBalance"), str(m, "currencyCode"));
    }

    @SuppressWarnings("rawtypes")
    private NubBankTransactionDto toTxDto(Map m) {
        return new NubBankTransactionDto(
            str(m, "id"), str(m, "transactionType"),
            decimal(m, "amount"), decimal(m, "runningBalance"),
            str(m, "currencyCode"), str(m, "reference"), str(m, "createdAt"));
    }

    @SuppressWarnings("rawtypes")
    private String str(Map m, String k) {
        return String.valueOf(m.getOrDefault(k, ""));
    }

    @SuppressWarnings("rawtypes")
    private BigDecimal decimal(Map m, String k) {
        return new BigDecimal(String.valueOf(m.getOrDefault(k, "0")));
    }
}
