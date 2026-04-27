package com.nubbank.baas.engine.sandbox;

import com.nubbank.baas.engine.account.dto.TransactionResponse;
import com.nubbank.baas.engine.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @PostMapping("/simulate/deposit")
    public ResponseEntity<ApiResponse<TransactionResponse>> simulateDeposit(
            @RequestBody Map<String, Object> body) {
        UUID accountId = UUID.fromString(body.get("accountId").toString());
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        return ResponseEntity.ok(ApiResponse.ok(sandboxService.simulateDeposit(accountId, amount)));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Map<String, String>>> reset() {
        sandboxService.reset();
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "status", "RESET_COMPLETE",
            "message", "All sandbox data has been wiped")));
    }
}
