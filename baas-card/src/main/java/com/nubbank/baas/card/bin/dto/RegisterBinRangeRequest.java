package com.nubbank.baas.card.bin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * BIN range registration body. Note there is NO partnerId/schemaName field —
 * those are taken from the authenticated PartnerContext, never the request body.
 */
public record RegisterBinRangeRequest(
    @NotBlank String binStart,
    @NotBlank String binEnd,
    String scheme
) {}
