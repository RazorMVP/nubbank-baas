package com.nubbank.baas.engine.compliance;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ComplianceService service;

    @GetMapping("/screening")
    public ResponseEntity<ApiResponse<Page<SanctionsScreeningLog>>> listScreenings(
            @RequestParam String entityType,
            @RequestParam UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        return ResponseEntity.ok(ApiResponse.ok(
            service.listScreenings(entityType, entityId, page, size)));
    }

    @PostMapping("/screening/customers/{customerId}")
    public ResponseEntity<ApiResponse<SanctionsScreeningResult>> screenCustomer(
            @PathVariable UUID customerId) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        return ResponseEntity.ok(ApiResponse.ok(service.screenCustomer(customerId)));
    }
}
