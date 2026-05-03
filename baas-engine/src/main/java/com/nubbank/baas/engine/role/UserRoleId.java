package com.nubbank.baas.engine.role;

import java.io.Serializable;
import java.util.UUID;

public record UserRoleId(UUID userId, UUID role) implements Serializable {}
