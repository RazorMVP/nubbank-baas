package com.nubbank.baas.card.stats;

/** Card count for a partner schema, consumed by the engine dashboard (DEF-1C-29). */
public record CardStatsResponse(long cardsIssued) {}
