package com.nubbank.baas.engine.survey.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record SurveyRequest(
    @NotBlank String key,
    @NotBlank String name,
    String description,
    String countryCode,
    @NotNull List<QuestionRequest> questions
) {
    public record QuestionRequest(
        @NotBlank String question, int sequenceNo,
        List<ResponseRequest> responses
    ) {}
    public record ResponseRequest(@NotBlank String response, int value, int sequenceNo) {}
}
