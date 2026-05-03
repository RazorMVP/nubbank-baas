package com.nubbank.baas.engine.standing;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.standing.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class StandingInstructionController {

    private final StandingInstructionService service;

    @PostMapping("/baas/v1/standinginstructions")
    public ResponseEntity<ApiResponse<StandingInstruction>> create(
            @Valid @RequestBody StandingInstructionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(req)));
    }

    @PostMapping("/baas/v1/standinginstructions/{id}")
    public ResponseEntity<ApiResponse<StandingInstruction>> command(
            @PathVariable UUID id, @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @GetMapping("/baas/v1/standinginstructions/customer/{customerId}")
    public ResponseEntity<ApiResponse<Page<StandingInstruction>>> listByCustomer(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listByCustomer(customerId, page, size)));
    }

    @PostMapping("/baas/v1/clients/{customerId}/beneficiaries")
    public ResponseEntity<ApiResponse<Beneficiary>> addBeneficiary(
            @PathVariable UUID customerId, @Valid @RequestBody BeneficiaryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addBeneficiary(customerId, req)));
    }

    @GetMapping("/baas/v1/clients/{customerId}/beneficiaries")
    public ResponseEntity<ApiResponse<Page<Beneficiary>>> listBeneficiaries(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listBeneficiaries(customerId, page, size)));
    }

    @DeleteMapping("/baas/v1/clients/{customerId}/beneficiaries/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteBeneficiary(
            @PathVariable UUID customerId, @PathVariable UUID id) {
        service.deleteBeneficiary(customerId, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
