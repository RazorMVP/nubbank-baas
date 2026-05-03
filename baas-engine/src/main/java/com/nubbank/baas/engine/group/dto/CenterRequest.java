package com.nubbank.baas.engine.group.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record CenterRequest(@NotBlank String name, String externalId,
    UUID officeId, UUID staffId, String meetingTime) {}
