package com.nubbank.baas.engine.role.dto;

import jakarta.validation.constraints.NotBlank;

public record RoleRequest(@NotBlank String name, String description) {}
