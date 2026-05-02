package com.nubbank.baas.engine.role.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

public record UpdatePermissionsRequest(@NotNull Set<UUID> permissionIds) {}
