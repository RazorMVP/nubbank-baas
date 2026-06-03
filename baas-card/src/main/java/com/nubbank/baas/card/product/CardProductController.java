package com.nubbank.baas.card.product;

import com.nubbank.baas.card.common.ApiResponse;
import com.nubbank.baas.card.product.dto.CardProductResponse;
import com.nubbank.baas.card.product.dto.CreateCardProductRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Partner-facing card-product management — {@code /baas/v1/card-products}.
 *
 * Auth is enforced upstream by the {@code @Order(2)} partner chain
 * (PartnerContextFilter resolves the JWT/ApiKey into PartnerContext;
 * AuthEnforcementFilter 401s if it's still null). The tenant (schema) is taken
 * from that context inside {@link CardProductService}, never from the request body.
 *
 * Operator {@code @PreAuthorize} RBAC is DEFERRED (matches the rest of baas-card).
 */
@RestController
@RequestMapping("/baas/v1/card-products")
@RequiredArgsConstructor
public class CardProductController {

    private final CardProductService cardProductService;

    @PostMapping
    public ResponseEntity<ApiResponse<CardProductResponse>> create(
            @Valid @RequestBody CreateCardProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(cardProductService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CardProductResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(cardProductService.list()));
    }
}
