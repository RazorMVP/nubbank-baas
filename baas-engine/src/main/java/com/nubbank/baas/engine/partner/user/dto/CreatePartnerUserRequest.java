package com.nubbank.baas.engine.partner.user.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record CreatePartnerUserRequest(
    @Email @NotBlank String email,
    @Size(min = 8) @NotBlank String password,
    @NotEmpty Set<UUID> roleIds
) {}
