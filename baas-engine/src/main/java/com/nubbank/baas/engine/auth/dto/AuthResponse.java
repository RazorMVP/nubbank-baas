package com.nubbank.baas.engine.auth.dto;

public record AuthResponse(
    String token,
    String partnerId,
    String schemaName,
    String tier,
    String environment,
    String role,
    String orgName
) {}
