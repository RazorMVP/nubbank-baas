package com.nubbank.baas.engine.deposit;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.deposit.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/recurring-deposits")
@RequiredArgsConstructor
public class RecurringDepositController {
    private final RecurringDepositService service;

    @PostMapping
    public ResponseEntity<ApiResponse<RecurringDepositResponse>> create(@Valid @RequestBody RecurringDepositRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RecurringDepositResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping("/{id}")
    public ResponseEntity<ApiResponse<RecurringDepositResponse>> command(
            @PathVariable UUID id, @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<Page<RecurringDepositResponse>>> listByCustomer(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByCustomer(customerId, page, size)));
    }
}
