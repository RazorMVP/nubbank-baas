package com.nubbank.baas.card.engine.dto;

/** Card→engine credit (reversal) request. */
public record CardCreditRequest(String partnerId, String schemaName, String authKey) {}
