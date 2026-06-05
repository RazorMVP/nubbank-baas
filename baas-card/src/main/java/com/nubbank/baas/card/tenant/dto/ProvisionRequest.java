package com.nubbank.baas.card.tenant.dto;

import java.util.UUID;

/** Engine→card provisioning trigger body (DEF-1C-22). */
public record ProvisionRequest(UUID partnerId, String schemaName) {}
