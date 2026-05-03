package com.nubbank.baas.engine.twofa;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.twofa.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/baas/v1/twofactor")
@RequiredArgsConstructor
public class TwoFactorController {

    private final TwoFactorService service;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(
            @Valid @RequestBody GenerateOtpRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.generateOtp(req)));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @Valid @RequestBody VerifyOtpRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.verifyOtp(req)));
    }
}
