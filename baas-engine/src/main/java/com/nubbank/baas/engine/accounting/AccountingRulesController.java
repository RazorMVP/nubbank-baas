package com.nubbank.baas.engine.accounting;

import com.nubbank.baas.engine.accounting.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AccountingRulesController {

    private final AccountingRulesService service;

    @PostMapping("/baas/v1/accountingrules")
    public ResponseEntity<ApiResponse<AccountingRule>> createRule(@Valid @RequestBody AccountingRuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createRule(req)));
    }

    @GetMapping("/baas/v1/accountingrules")
    public ResponseEntity<ApiResponse<List<AccountingRule>>> listRules() {
        return ResponseEntity.ok(ApiResponse.ok(service.listRules()));
    }

    @DeleteMapping("/baas/v1/accountingrules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable UUID id) {
        service.deleteRule(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/provisioningcriteria")
    public ResponseEntity<ApiResponse<ProvisioningCriteria>> createCriteria(@Valid @RequestBody ProvisioningCriteriaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createCriteria(req)));
    }

    @GetMapping("/baas/v1/provisioningcriteria")
    public ResponseEntity<ApiResponse<List<ProvisioningCriteria>>> listCriteria() {
        return ResponseEntity.ok(ApiResponse.ok(service.listCriteria()));
    }

    @PutMapping("/baas/v1/provisioningcriteria/{id}")
    public ResponseEntity<ApiResponse<ProvisioningCriteria>> updateCriteria(
            @PathVariable UUID id, @Valid @RequestBody ProvisioningCriteriaRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateCriteria(id, req)));
    }

    @DeleteMapping("/baas/v1/provisioningcriteria/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCriteria(@PathVariable UUID id) {
        service.deleteCriteria(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
