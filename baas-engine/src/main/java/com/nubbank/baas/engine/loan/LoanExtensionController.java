package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.loan.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/baas/v1/loans/{loanId}")
@RequiredArgsConstructor
public class LoanExtensionController {

    private final LoanExtensionService service;

    @PostMapping("/guarantors")
    public ResponseEntity<ApiResponse<LoanGuarantor>> addGuarantor(
            @PathVariable UUID loanId,
            @RequestBody GuarantorRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addGuarantor(loanId, req)));
    }

    @GetMapping("/guarantors")
    public ResponseEntity<ApiResponse<List<LoanGuarantor>>> listGuarantors(@PathVariable UUID loanId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listGuarantors(loanId)));
    }

    @DeleteMapping("/guarantors/{guarantorId}")
    public ResponseEntity<ApiResponse<Void>> deleteGuarantor(
            @PathVariable UUID loanId,
            @PathVariable UUID guarantorId) {
        service.deleteGuarantor(loanId, guarantorId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/collaterals")
    public ResponseEntity<ApiResponse<LoanCollateral>> addCollateral(
            @PathVariable UUID loanId,
            @Valid @RequestBody CollateralRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addCollateral(loanId, req)));
    }

    @GetMapping("/collaterals")
    public ResponseEntity<ApiResponse<List<LoanCollateral>>> listCollaterals(@PathVariable UUID loanId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listCollaterals(loanId)));
    }

    @DeleteMapping("/collaterals/{collateralId}")
    public ResponseEntity<ApiResponse<Void>> deleteCollateral(
            @PathVariable UUID loanId,
            @PathVariable UUID collateralId) {
        service.deleteCollateral(loanId, collateralId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/reschedule")
    public ResponseEntity<ApiResponse<LoanRescheduleRequest>> createReschedule(
            @PathVariable UUID loanId,
            @RequestBody RescheduleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createReschedule(loanId, req)));
    }

    @GetMapping("/reschedule")
    public ResponseEntity<ApiResponse<List<LoanRescheduleRequest>>> listReschedules(@PathVariable UUID loanId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listReschedules(loanId)));
    }

    @PostMapping("/reschedule/{rescheduleId}")
    public ResponseEntity<ApiResponse<LoanRescheduleRequest>> approveReschedule(
            @PathVariable UUID loanId,
            @PathVariable UUID rescheduleId) {
        return ResponseEntity.ok(ApiResponse.ok(service.approveReschedule(rescheduleId)));
    }
}
