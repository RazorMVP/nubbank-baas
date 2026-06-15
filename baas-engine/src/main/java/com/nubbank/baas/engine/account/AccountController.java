package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> open(
            @Valid @RequestBody OpenAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(accountService.open(req)));
    }

    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AccountSummaryResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.list(page, size, status, search)));
    }

    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getById(id)));
    }

    @PreAuthorize("hasAuthority('READ_ACCOUNT')")
    @GetMapping("/{id}/status-events")
    public ResponseEntity<ApiResponse<List<AccountStatusEventResponse>>> statusEvents(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.statusEvents(id)));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
            @PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.deposit(id, req)));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            @PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.withdraw(id, req)));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> transactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.getTransactions(id, page, size)));
    }

    @PreAuthorize("hasAuthority('UPDATE_ACCOUNT')")
    @PostMapping("/{id}/freeze")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> freeze(
            @PathVariable UUID id, @Valid @RequestBody AccountTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            accountService.transition(id, AccountCommand.FREEZE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_ACCOUNT')")
    @PostMapping("/{id}/unfreeze")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> unfreeze(
            @PathVariable UUID id, @Valid @RequestBody AccountTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            accountService.transition(id, AccountCommand.UNFREEZE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_ACCOUNT')")
    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<AccountDetailResponse>> close(
            @PathVariable UUID id, @Valid @RequestBody AccountTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            accountService.transition(id, AccountCommand.CLOSE, req.reason())));
    }
}
