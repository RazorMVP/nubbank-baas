package com.nubbank.baas.card.engine.dto;

import java.util.UUID;

/** Card→engine account-existence lookup request (issuance validation). */
public record AccountLookupRequest(String partnerId, String schemaName, UUID accountId) {}
