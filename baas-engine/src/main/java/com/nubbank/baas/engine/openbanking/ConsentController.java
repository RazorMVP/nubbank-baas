package com.nubbank.baas.engine.openbanking;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.openbanking.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/open-banking/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService service;

    @PostMapping
    public ResponseEntity<ApiResponse<ConsentResponse>> create(
            @Valid @RequestBody CreateConsentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConsentResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ConsentResponse>>> list(
            @RequestParam(required = false) String tppClientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(tppClientId, page, size)));
    }

    @PutMapping("/{id}/authorise")
    public ResponseEntity<ApiResponse<ConsentResponse>> authorise(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.authorise(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ConsentResponse>> revoke(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.revoke(id)));
    }
}
