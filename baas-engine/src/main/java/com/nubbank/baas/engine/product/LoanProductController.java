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
@RequestMapping("/baas/v1/loan-products")
@RequiredArgsConstructor
public class LoanProductController {

    private final LoanProductService service;

    @PostMapping
    public ResponseEntity<ApiResponse<LoanProductResponse>> create(@Valid @RequestBody LoanProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LoanProductResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<LoanProductResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(page, size)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LoanProductResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody LoanProductRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
