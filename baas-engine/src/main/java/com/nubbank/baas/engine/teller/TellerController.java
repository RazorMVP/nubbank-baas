package com.nubbank.baas.engine.teller;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.teller.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/tellers")
@RequiredArgsConstructor
public class TellerController {

    private final TellerService service;

    @PostMapping
    public ResponseEntity<ApiResponse<Teller>> create(@Valid @RequestBody TellerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createTeller(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Teller>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Teller>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping("/{id}")
    public ResponseEntity<ApiResponse<Teller>> command(@PathVariable UUID id, @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @PostMapping("/{tellerId}/cashiers")
    public ResponseEntity<ApiResponse<Cashier>> addCashier(@PathVariable UUID tellerId, @RequestBody CashierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.addCashier(tellerId, req)));
    }

    @PostMapping("/{tellerId}/sessions")
    public ResponseEntity<ApiResponse<TellerSession>> openSession(
            @PathVariable UUID tellerId, @Valid @RequestBody OpenSessionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.openSession(tellerId, req)));
    }

    @GetMapping("/{tellerId}/sessions")
    public ResponseEntity<ApiResponse<List<TellerSession>>> listSessions(@PathVariable UUID tellerId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listSessions(tellerId)));
    }

    @PostMapping("/{tellerId}/sessions/{sessionId}/transactions")
    public ResponseEntity<ApiResponse<CashTransaction>> addCashTransaction(
            @PathVariable UUID tellerId, @PathVariable UUID sessionId,
            @Valid @RequestBody CashTransactionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addCashTransaction(tellerId, sessionId, req)));
    }

    @PostMapping("/{tellerId}/sessions/{sessionId}/settle")
    public ResponseEntity<ApiResponse<TellerSession>> settle(
            @PathVariable UUID tellerId, @PathVariable UUID sessionId,
            @Valid @RequestBody SettleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.settleSession(tellerId, sessionId, req)));
    }
}
