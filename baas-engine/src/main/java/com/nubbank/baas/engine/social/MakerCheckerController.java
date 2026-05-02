package com.nubbank.baas.engine.social;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.social.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class MakerCheckerController {

    private final MakerCheckerService service;

    @PostMapping("/baas/v1/makercheckers")
    public ResponseEntity<ApiResponse<MakerCheckerRequest>> create(
            @Valid @RequestBody MakerCheckerCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/baas/v1/makercheckers")
    public ResponseEntity<ApiResponse<Page<MakerCheckerRequest>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(status, page, size)));
    }

    @PostMapping("/baas/v1/makercheckers/{id}")
    public ResponseEntity<ApiResponse<MakerCheckerRequest>> command(
            @PathVariable UUID id,
            @RequestParam String command) {
        // Checker identity is derived from the JWT (PartnerContext.userId),
        // not accepted as a request parameter — see MakerCheckerService.
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @DeleteMapping("/baas/v1/makercheckers/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/datatables")
    public ResponseEntity<ApiResponse<DataTableRegistration>> registerDataTable(
            @Valid @RequestBody DataTableRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.registerDataTable(req)));
    }

    @GetMapping("/baas/v1/datatables")
    public ResponseEntity<ApiResponse<List<DataTableRegistration>>> listDataTables() {
        return ResponseEntity.ok(ApiResponse.ok(service.listDataTables()));
    }

    @DeleteMapping("/baas/v1/datatables/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDataTable(@PathVariable UUID id) {
        service.deleteDataTable(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
