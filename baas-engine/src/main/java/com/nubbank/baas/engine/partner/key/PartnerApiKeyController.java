package com.nubbank.baas.engine.partner.key;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.partner.key.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/baas/v1/partner-api-keys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_API_KEYS')")
public class PartnerApiKeyController {

    private final PartnerApiKeyService service;

    @PostMapping
    public ResponseEntity<ApiResponse<IssuedApiKeyResponse>> issue(
            @Valid @RequestBody IssueApiKeyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.issue(req)));
    }
}
