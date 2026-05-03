package com.nubbank.baas.engine.product;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.product.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/deposit-products")
@RequiredArgsConstructor
public class DepositProductController {

    private final DepositProductService service;

    @PostMapping
    public ResponseEntity<ApiResponse<DepositProductResponse>> create(@Valid @RequestBody DepositProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DepositProductResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<DepositProductResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(page, size)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DepositProductResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody DepositProductRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
