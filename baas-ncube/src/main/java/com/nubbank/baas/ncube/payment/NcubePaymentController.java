package com.nubbank.baas.ncube.payment;

import com.nubbank.baas.ncube.common.*;
import com.nubbank.baas.ncube.payment.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/baas/v1/ncube/payments")
@RequiredArgsConstructor
public class NcubePaymentController {

    private final NipPaymentOrchestrator orchestrator;

    @PostMapping("/nip")
    public ResponseEntity<CbnApiResponse<NipPaymentResponse>> initiateNip(
            @Valid @RequestBody NipPaymentRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return ResponseEntity.ok(new CbnApiResponse<>(
            orchestrator.initiate(req, auth),
            new CbnLinks("https://api.nubbank.com/baas/v1/ncube/payments/nip"),
            new CbnMeta(1)));
    }
}
