package com.nubbank.baas.card.card;

import com.nubbank.baas.card.card.dto.CardResponse;
import com.nubbank.baas.card.card.dto.IssueCardRequest;
import com.nubbank.baas.card.common.ApiResponse;
import com.nubbank.baas.card.limit.CardLimitService;
import com.nubbank.baas.card.limit.dto.CardLimitResponse;
import com.nubbank.baas.card.limit.dto.UpdateCardLimitsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Partner-facing card issuance + lifecycle — {@code /baas/v1/cards}.
 *
 * <p>Auth is enforced upstream by the {@code @Order(2)} partner chain
 * (PartnerContextFilter resolves the JWT/ApiKey into PartnerContext;
 * AuthEnforcementFilter 401s if it's still null). The tenant (schema) is taken from
 * that context inside {@link CardService}, never from the request body.
 *
 * <p>Operator {@code @PreAuthorize} RBAC is DEFERRED (matches the rest of baas-card —
 * the partner chain enforces first-party auth).
 */
@RestController
@RequestMapping("/baas/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;
    private final CardLimitService cardLimitService;

    @PostMapping
    public ResponseEntity<ApiResponse<CardResponse>> issue(
            @Valid @RequestBody IssueCardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(cardService.issue(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CardResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(cardService.list()));
    }

    @PostMapping("/{id}")
    public ResponseEntity<ApiResponse<CardResponse>> command(
            @PathVariable UUID id,
            @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(cardService.executeCommand(id, command)));
    }

    // ---- per-card limits (delegated to CardLimitService) ----

    @PutMapping("/{id}/limits")
    public ResponseEntity<ApiResponse<CardLimitResponse>> updateLimits(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCardLimitsRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(cardLimitService.updateLimits(id, request)));
    }

    @GetMapping("/{id}/limits")
    public ResponseEntity<ApiResponse<CardLimitResponse>> getLimits(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(cardLimitService.getLimits(id)));
    }
}
