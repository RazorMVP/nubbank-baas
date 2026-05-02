package com.nubbank.baas.engine.social.dto;

import jakarta.validation.constraints.NotBlank;

public record NoteRequest(@NotBlank String note) {}
