package com.nubbank.baas.engine.accounting;

import com.nubbank.baas.engine.accounting.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class GlAccountingController {

    private final GlAccountingService service;

    @PostMapping("/baas/v1/glaccounts")
    public ResponseEntity<ApiResponse<GlAccountResponse>> createGlAccount(@Valid @RequestBody GlAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createGlAccount(req)));
    }

    @GetMapping("/baas/v1/glaccounts")
    public ResponseEntity<ApiResponse<Page<GlAccountResponse>>> listGlAccounts(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listAccounts(page, size)));
    }

    @PutMapping("/baas/v1/glaccounts/{id}")
    public ResponseEntity<ApiResponse<GlAccountResponse>> updateGlAccount(
            @PathVariable UUID id, @Valid @RequestBody GlAccountRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateGlAccount(id, req)));
    }

    @DeleteMapping("/baas/v1/glaccounts/{id}")
    public ResponseEntity<ApiResponse<Void>> disableGlAccount(@PathVariable UUID id) {
        service.disableGlAccount(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/journalentries")
    public ResponseEntity<ApiResponse<JournalEntry>> postJournalEntry(@Valid @RequestBody JournalEntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.postManualJournalEntry(req)));
    }

    @PostMapping("/baas/v1/journalentries/{id}/reverse")
    public ResponseEntity<ApiResponse<JournalEntry>> reverseJournalEntry(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.reverseJournalEntry(id)));
    }

    @PostMapping("/baas/v1/glclosures")
    public ResponseEntity<ApiResponse<GlClosure>> createClosure(@Valid @RequestBody GlClosureRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createClosure(req)));
    }

    @GetMapping("/baas/v1/glclosures")
    public ResponseEntity<ApiResponse<List<GlClosure>>> listClosures() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAllClosures()));
    }

    @PostMapping("/baas/v1/financialactivityaccounts")
    public ResponseEntity<ApiResponse<FinancialActivityAccount>> upsertFinancialActivity(
            @Valid @RequestBody FinancialActivityRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.upsertFinancialActivity(req)));
    }

    @GetMapping("/baas/v1/financialactivityaccounts")
    public ResponseEntity<ApiResponse<List<FinancialActivityAccount>>> listFinancialActivities() {
        return ResponseEntity.ok(ApiResponse.ok(service.listFinancialActivities()));
    }
}
