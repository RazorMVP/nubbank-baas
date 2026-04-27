package com.nubbank.baas.ncube.consent.dto;

import java.util.List;

public record NubBankConsentDto(
    String id, String status, List<String> scopes,
    String tppClientId, String expiryDate, String createdAt
) {}
