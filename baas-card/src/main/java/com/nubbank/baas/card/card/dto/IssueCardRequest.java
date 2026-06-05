package com.nubbank.baas.card.card.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Issue-card body. There is NO partnerId/schemaName field — the tenant is taken
 * from the authenticated PartnerContext (the schema is the isolation boundary),
 * never the request body.
 *
 * @param productId       the card product to issue against (must exist in this tenant)
 * @param customerRef     opaque partner-side customer reference (optional)
 * @param virtual         whether this is a virtual card
 * @param linkedAccountId the engine account this card draws from — validated at issuance
 */
public record IssueCardRequest(
    @NotNull(message = "productId is required") UUID productId,
    String customerRef,
    boolean virtual,
    @NotNull(message = "linkedAccountId is required") UUID linkedAccountId
) {}
