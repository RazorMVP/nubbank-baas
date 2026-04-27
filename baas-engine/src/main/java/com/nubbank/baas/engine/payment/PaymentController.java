package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.payment.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/baas/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody TransferRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.transfer(req)));
    }
}
