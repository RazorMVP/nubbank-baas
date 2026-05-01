package com.nubbank.baas.engine.office;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.office.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OfficeController {

    private final OfficeService service;

    @PostMapping("/baas/v1/offices")
    public ResponseEntity<ApiResponse<Office>> createOffice(@Valid @RequestBody OfficeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createOffice(req)));
    }

    @GetMapping("/baas/v1/offices")
    public ResponseEntity<ApiResponse<List<Office>>> listOffices() {
        return ResponseEntity.ok(ApiResponse.ok(service.listOffices()));
    }

    @PutMapping("/baas/v1/offices/{id}")
    public ResponseEntity<ApiResponse<Office>> updateOffice(
            @PathVariable UUID id, @Valid @RequestBody OfficeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateOffice(id, req)));
    }

    @PostMapping("/baas/v1/staff")
    public ResponseEntity<ApiResponse<Staff>> createStaff(@Valid @RequestBody StaffRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createStaff(req)));
    }

    @GetMapping("/baas/v1/staff")
    public ResponseEntity<ApiResponse<Page<Staff>>> listStaff(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listStaff(page, size)));
    }

    @PutMapping("/baas/v1/staff/{id}")
    public ResponseEntity<ApiResponse<Staff>> updateStaff(
            @PathVariable UUID id, @Valid @RequestBody StaffRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateStaff(id, req)));
    }

    @DeleteMapping("/baas/v1/staff/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteStaff(@PathVariable UUID id) {
        service.deleteStaff(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
