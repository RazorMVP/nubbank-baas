package com.nubbank.baas.card.stats;

/** Internal stats request — the partner whose schema the card count is read from. */
public record CardStatsRequest(String partnerId, String schemaName) {}
