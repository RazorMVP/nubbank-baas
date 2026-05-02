package com.nubbank.baas.engine.survey.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ScorecardRequest(
    @NotNull UUID customerId,
    @NotNull List<ScoreEntry> scores
) {
    public record ScoreEntry(@NotNull UUID questionId, @NotNull UUID responseId, int score) {}
}
