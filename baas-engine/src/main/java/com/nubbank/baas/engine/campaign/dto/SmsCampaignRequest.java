package com.nubbank.baas.engine.campaign.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsCampaignRequest(@NotBlank String name, String campaignType, String triggerType,
    @NotBlank String messageTemplate, String recurrence) {}
