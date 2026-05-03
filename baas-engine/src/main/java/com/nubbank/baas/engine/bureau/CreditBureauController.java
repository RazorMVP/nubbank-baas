package com.nubbank.baas.engine.bureau;

import com.nubbank.baas.engine.bureau.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CreditBureauController {

    private final CreditBureauService service;

    @PostMapping("/baas/v1/creditbureaus")
    public ResponseEntity<ApiResponse<CreditBureauIntegration>> create(
            @Valid @RequestBody CreditBureauRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/baas/v1/creditbureaus")
    public ResponseEntity<ApiResponse<List<CreditBureauIntegration>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @PostMapping("/baas/v1/creditbureaus/{id}")
    public ResponseEntity<ApiResponse<CreditBureauIntegration>> command(
            @PathVariable UUID id, @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @PostMapping("/baas/v1/creditbureaus/{id}/mappings")
    public ResponseEntity<ApiResponse<CreditBureauProductMapping>> addMapping(
            @PathVariable UUID id, @Valid @RequestBody BureauMappingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addMapping(id, req)));
    }

    @DeleteMapping("/baas/v1/creditbureaus/{bureauId}/mappings/{mappingId}")
    public ResponseEntity<ApiResponse<Void>> deleteMapping(
            @PathVariable UUID bureauId, @PathVariable UUID mappingId) {
        service.deleteMapping(mappingId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
