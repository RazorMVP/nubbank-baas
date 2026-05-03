package com.nubbank.baas.engine.social.dto;

import jakarta.validation.constraints.NotBlank;

public record DocumentRequest(@NotBlank String fileName, String contentType,
    Long fileSizeBytes, String storagePath, String description) {}
