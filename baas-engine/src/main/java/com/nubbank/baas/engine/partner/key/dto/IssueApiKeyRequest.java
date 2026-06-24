package com.nubbank.baas.engine.partner.key.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record IssueApiKeyRequest(@NotBlank String name, List<String> scopes) {}
