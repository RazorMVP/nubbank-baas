package com.nubbank.baas.fep.routing;

import java.util.UUID;

/**
 * Resolved routing context for an incoming card transaction.
 * Returned by {@link BinResolver} once a BIN is matched in the Card service.
 *
 * @param partnerId  UUID of the partner that owns this BIN range.
 * @param schemaName PostgreSQL schema (e.g. {@code "partner_<uuid>"}) used by baas-card
 *                   to scope the authorization to the correct tenant database.
 */
public record PartnerRoute(UUID partnerId, String schemaName) {}
