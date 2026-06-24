package com.nubbank.baas.engine.partner.user.dto;

import java.util.*;

public record PartnerUserResponse(UUID id, String email, boolean active, List<String> roles) {}
