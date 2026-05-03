package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.loan.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService service;

    @PostMapping
    public ResponseEntity<ApiResponse<LoanResponse>> apply(@Valid @RequestBody ApplyLoanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.apply(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LoanResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping("/{id}")
    public ResponseEntity<ApiResponse<LoanResponse>> command(
            @PathVariable UUID id, @RequestParam String command,
            @RequestBody(required = false) LoanCommandRequest body) {
        String note = body != null ? (body.note() != null ? body.note() : body.rejectionReason()) : null;
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command, note)));
    }

    @PostMapping("/{id}/repayments")
    public ResponseEntity<ApiResponse<LoanResponse>> repay(
            @PathVariable UUID id, @Valid @RequestBody RepayRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.repay(id, req.amount())));
    }

    @GetMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<Page<ScheduleLineResponse>>> getSchedule(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.getSchedule(id, page, size)));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<Page<LoanResponse>>> listByCustomer(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByCustomer(customerId, page, size)));
    }
}
