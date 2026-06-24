package com.nubbank.baas.engine.partner.user.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.*;

public record UpdateUserRolesRequest(@NotEmpty Set<UUID> roleIds) {}
