package com.nubbank.baas.engine.system;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.system.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService service;

    @GetMapping("/baas/v1/configurations")
    public ResponseEntity<ApiResponse<Page<SystemConfiguration>>> listConfigs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listConfigs(page, size)));
    }

    @PutMapping("/baas/v1/configurations/{key}")
    public ResponseEntity<ApiResponse<SystemConfiguration>> updateConfig(
            @PathVariable String key, @RequestBody SystemConfigUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateConfig(key, req)));
    }

    @PostMapping("/baas/v1/codes")
    public ResponseEntity<ApiResponse<Code>> createCode(@Valid @RequestBody CodeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createCode(req)));
    }

    @GetMapping("/baas/v1/codes")
    public ResponseEntity<ApiResponse<List<Code>>> listCodes() {
        return ResponseEntity.ok(ApiResponse.ok(service.listCodes()));
    }

    @DeleteMapping("/baas/v1/codes/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCode(@PathVariable UUID id) {
        service.deleteCode(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/codes/{codeId}/codevalues")
    public ResponseEntity<ApiResponse<CodeValue>> addCodeValue(
            @PathVariable UUID codeId, @Valid @RequestBody CodeValueRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addCodeValue(codeId, req)));
    }

    @GetMapping("/baas/v1/codes/{codeId}/codevalues")
    public ResponseEntity<ApiResponse<List<CodeValue>>> listCodeValues(@PathVariable UUID codeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listCodeValues(codeId)));
    }

    @DeleteMapping("/baas/v1/codes/{codeId}/codevalues/{valueId}")
    public ResponseEntity<ApiResponse<Void>> deleteCodeValue(
            @PathVariable UUID codeId, @PathVariable UUID valueId) {
        service.deleteCodeValue(valueId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/paymenttypes")
    public ResponseEntity<ApiResponse<PaymentType>> createPaymentType(
            @Valid @RequestBody PaymentTypeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createPaymentType(req)));
    }

    @GetMapping("/baas/v1/paymenttypes")
    public ResponseEntity<ApiResponse<Page<PaymentType>>> listPaymentTypes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listPaymentTypes(page, size)));
    }

    @DeleteMapping("/baas/v1/paymenttypes/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePaymentType(@PathVariable UUID id) {
        service.deletePaymentType(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/holidays")
    public ResponseEntity<ApiResponse<Holiday>> createHoliday(@Valid @RequestBody HolidayRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createHoliday(req)));
    }

    @GetMapping("/baas/v1/holidays")
    public ResponseEntity<ApiResponse<Page<Holiday>>> listHolidays(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listHolidays(page, size)));
    }

    @PostMapping("/baas/v1/holidays/{id}")
    public ResponseEntity<ApiResponse<Holiday>> activateHoliday(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.activateHoliday(id)));
    }

    @DeleteMapping("/baas/v1/holidays/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteHoliday(@PathVariable UUID id) {
        service.deleteHoliday(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
