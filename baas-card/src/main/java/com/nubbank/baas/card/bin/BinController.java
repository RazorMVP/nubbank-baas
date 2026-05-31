package com.nubbank.baas.card.bin;

import com.nubbank.baas.card.bin.dto.BinRangeResponse;
import com.nubbank.baas.card.bin.dto.RegisterBinRangeRequest;
import com.nubbank.baas.card.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Partner-facing BIN range management — {@code /baas/v1/bins}.
 *
 * Auth is enforced upstream by the {@code @Order(2)} partner chain
 * (PartnerContextFilter resolves the JWT/ApiKey into PartnerContext;
 * AuthEnforcementFilter 401s if it's still null). partnerId/schemaName are taken
 * from that context inside {@link BinService}, never from the request body.
 *
 * Operator {@code @PreAuthorize} RBAC is DEFERRED (matches the rest of baas-card).
 */
@RestController
@RequestMapping("/baas/v1/bins")
@RequiredArgsConstructor
public class BinController {

    private final BinService binService;

    @PostMapping
    public ResponseEntity<ApiResponse<BinRangeResponse>> register(
            @Valid @RequestBody RegisterBinRangeRequest request) {
        CardBinRange saved = binService.register(request.binStart(), request.binEnd(), request.scheme());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(BinRangeResponse.from(saved)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BinRangeResponse>>> list() {
        List<BinRangeResponse> ranges = binService.listForCurrentPartner().stream()
            .map(BinRangeResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.ok(ranges));
    }
}
