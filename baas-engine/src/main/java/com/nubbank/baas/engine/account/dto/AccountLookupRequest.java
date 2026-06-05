package com.nubbank.baas.engine.account.dto;

import java.util.UUID;

/** Internal account-existence lookup body — used by card issuance to validate linkedAccountId. */
public record AccountLookupRequest(String partnerId, String schemaName, UUID accountId) {}
