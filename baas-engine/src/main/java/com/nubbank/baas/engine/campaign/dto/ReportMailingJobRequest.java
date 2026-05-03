package com.nubbank.baas.engine.campaign.dto;

import jakarta.validation.constraints.NotBlank;

public record ReportMailingJobRequest(@NotBlank String name, @NotBlank String reportName,
    @NotBlank String emailRecipients, String outputType, String recurrence) {}
