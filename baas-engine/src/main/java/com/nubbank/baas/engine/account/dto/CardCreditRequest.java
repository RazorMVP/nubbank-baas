package com.nubbank.baas.engine.account.dto;

/** Internal card-credit (reversal) request body (Stage 5). */
public record CardCreditRequest(String partnerId, String schemaName, String authKey) {}
