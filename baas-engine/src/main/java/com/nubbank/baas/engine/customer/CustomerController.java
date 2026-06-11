package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.customer.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PreAuthorize("hasAuthority('CREATE_CUSTOMER')")
    @PostMapping
    public ResponseEntity<ApiResponse<CustomerResponse>> create(
            @Valid @RequestBody CreateCustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(customerService.create(req)));
    }

    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.getById(id)));
    }

    @PreAuthorize("hasAuthority('READ_CUSTOMER')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(customerService.list(page, size)));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> activate(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.ACTIVATE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> suspend(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.SUSPEND, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> reactivate(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.REACTIVATE, req.reason())));
    }

    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> close(
            @PathVariable UUID id, @Valid @RequestBody KycTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            customerService.transition(id, CustomerService.KycCommand.CLOSE, req.reason())));
    }
}
