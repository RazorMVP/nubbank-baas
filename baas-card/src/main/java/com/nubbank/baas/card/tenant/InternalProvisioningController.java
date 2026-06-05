package com.nubbank.baas.card.tenant;

import com.nubbank.baas.card.common.ApiResponse;
import com.nubbank.baas.card.tenant.dto.ProvisionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal provisioning trigger (DEF-1C-22). The engine calls this after its own tenant
 * migrations so the card schema objects exist in the partner schema. Idempotent
 * ({@code CREATE SCHEMA IF NOT EXISTS} + Flyway). Guarded by the {@code @Order(1)} internal
 * chain ({@code InternalServiceAuthFilter}, inbound HMAC) — no auth annotation needed here.
 */
@RestController
@RequestMapping("/internal/v1/provision")
@RequiredArgsConstructor
public class InternalProvisioningController {

    private final TenantProvisioningService provisioningService;

    @PostMapping
    public ApiResponse<Void> provision(@RequestBody ProvisionRequest request) {
        provisioningService.provision(request.partnerId(), request.schemaName());
        return ApiResponse.ok(null);
    }
}
