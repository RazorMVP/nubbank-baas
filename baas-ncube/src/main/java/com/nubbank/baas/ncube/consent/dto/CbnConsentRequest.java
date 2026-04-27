package com.nubbank.baas.ncube.consent.dto;

import java.util.List;

public record CbnConsentRequest(CbnConsentInitiation Data, CbnRisk Risk) {

    public record CbnConsentInitiation(
        List<String> Permissions, String ExpirationDateTime,
        String TransactionFromDateTime, String TransactionToDateTime
    ) {}

    public record CbnRisk() {}
}
