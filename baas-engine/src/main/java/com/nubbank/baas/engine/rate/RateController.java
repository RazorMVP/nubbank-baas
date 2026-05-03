package com.nubbank.baas.engine.rate;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.rate.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RateController {

    private final RateService service;

    @PostMapping("/baas/v1/floatingrates")
    public ResponseEntity<ApiResponse<FloatingRate>> create(@Valid @RequestBody FloatingRateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createFloatingRate(req)));
    }

    @GetMapping("/baas/v1/floatingrates")
    public ResponseEntity<ApiResponse<List<FloatingRate>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listFloatingRates()));
    }

    @PutMapping("/baas/v1/floatingrates/{id}")
    public ResponseEntity<ApiResponse<FloatingRate>> update(
            @PathVariable UUID id, @Valid @RequestBody FloatingRateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateFloatingRate(id, req)));
    }

    @DeleteMapping("/baas/v1/floatingrates/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.deleteFloatingRate(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/taxes/components")
    public ResponseEntity<ApiResponse<TaxComponent>> createComponent(
            @Valid @RequestBody TaxComponentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createTaxComponent(req)));
    }

    @GetMapping("/baas/v1/taxes/components")
    public ResponseEntity<ApiResponse<List<TaxComponent>>> listComponents() {
        return ResponseEntity.ok(ApiResponse.ok(service.listTaxComponents()));
    }

    @DeleteMapping("/baas/v1/taxes/components/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteComponent(@PathVariable UUID id) {
        service.deleteTaxComponent(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/taxes/groups")
    public ResponseEntity<ApiResponse<TaxGroup>> createGroup(@Valid @RequestBody TaxGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createTaxGroup(req)));
    }

    @GetMapping("/baas/v1/taxes/groups")
    public ResponseEntity<ApiResponse<List<TaxGroup>>> listGroups() {
        return ResponseEntity.ok(ApiResponse.ok(service.listTaxGroups()));
    }

    @DeleteMapping("/baas/v1/taxes/groups/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable UUID id) {
        service.deleteTaxGroup(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
