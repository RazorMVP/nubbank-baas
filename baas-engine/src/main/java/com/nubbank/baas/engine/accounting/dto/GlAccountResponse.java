package com.nubbank.baas.engine.accounting.dto;

import com.nubbank.baas.engine.accounting.GlAccountType;
import java.time.Instant;
import java.util.UUID;

public record GlAccountResponse(
    UUID id,
    String name,
    String glCode,
    GlAccountType accountType,
    String accountUsage,
    UUID parentId,
    boolean manualJournalEntriesAllowed,
    boolean disabled,
    Instant createdAt
) {}
