package com.nubbank.baas.engine.share;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.share.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService service;

    @PostMapping("/baas/v1/share-products")
    public ResponseEntity<ApiResponse<ShareProductResponse>> createProduct(
            @Valid @RequestBody ShareProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createProduct(req)));
    }

    @GetMapping("/baas/v1/share-products/{id}")
    public ResponseEntity<ApiResponse<ShareProductResponse>> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getProduct(id)));
    }

    @PostMapping("/baas/v1/share-accounts")
    public ResponseEntity<ApiResponse<ShareAccountResponse>> openAccount(
            @Valid @RequestBody ShareAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.openAccount(req)));
    }

    @GetMapping("/baas/v1/share-accounts/{id}")
    public ResponseEntity<ApiResponse<ShareAccountResponse>> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getAccount(id)));
    }

    @PostMapping("/baas/v1/share-accounts/{id}")
    public ResponseEntity<ApiResponse<ShareAccountResponse>> command(
            @PathVariable UUID id, @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @PostMapping("/baas/v1/share-accounts/{id}/transactions")
    public ResponseEntity<ApiResponse<ShareTransactionResponse>> transaction(
            @PathVariable UUID id,
            @RequestParam String type,
            @Valid @RequestBody ShareTransactionRequest req) {
        ShareTransactionResponse tx;
        if ("purchase".equalsIgnoreCase(type)) {
            tx = service.purchaseShares(id, req);
        } else if ("redeem".equalsIgnoreCase(type)) {
            tx = service.redeemShares(id, req);
        } else {
            throw BaasException.badRequest("INVALID_TYPE", "type must be purchase or redeem");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(tx));
    }

    @GetMapping("/baas/v1/share-accounts/customer/{customerId}")
    public ResponseEntity<ApiResponse<Page<ShareAccountResponse>>> listByCustomer(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByCustomer(customerId, page, size)));
    }
}
