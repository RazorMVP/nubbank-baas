package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionResponse;
import com.nubbank.baas.card.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (service-to-service) card-authorization decision — FROZEN CROSS-TRACK
 * CONTRACT §2a.
 *
 * <p>{@code POST /internal/v1/authorize} with body
 * {@code { partnerId, schemaName, pan, amountMinor, currency }} → 200 with the
 * {@link AuthorizationDecisionResponse} ({@code { decision, responseCode, message }})
 * carried as the {@code data} payload of the standard {@code ApiResponse} envelope —
 * the FEP's {@code HttpCardClient} reads {@code .data}.
 *
 * <p>No auth annotation here: the {@code @Order(1)} internal security chain runs
 * {@code InternalServiceAuthFilter} (inbound HMAC), which 401s any unsigned call
 * before it reaches this controller. The {@code PartnerContext} is set/cleared by
 * {@link AuthorizationDecisionService} (the FEP is a tenant-less caller that passes
 * the resolved {@code schemaName}).
 *
 * <p>SECURITY: the request carries the full PAN — it is NEVER logged here or anywhere
 * downstream.
 */
@RestController
@RequestMapping("/internal/v1/authorize")
@RequiredArgsConstructor
public class InternalAuthorizationController {

    private final AuthorizationDecisionService decisionService;

    @PostMapping
    public ApiResponse<AuthorizationDecisionResponse> authorize(
            @RequestBody AuthorizationDecisionRequest request) {
        return ApiResponse.ok(decisionService.decide(request));
    }
}
