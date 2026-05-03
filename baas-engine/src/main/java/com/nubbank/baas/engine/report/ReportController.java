package com.nubbank.baas.engine.report;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.report.dto.ReportRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService service;

    @GetMapping("/baas/v1/reports")
    public ResponseEntity<ApiResponse<Page<Report>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listReports(page, size)));
    }

    @GetMapping("/baas/v1/reports/{id}")
    public ResponseEntity<ApiResponse<Report>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping("/baas/v1/reports")
    public ResponseEntity<ApiResponse<Report>> create(@Valid @RequestBody ReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @DeleteMapping("/baas/v1/reports/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/baas/v1/runreports/{reportName}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> runReport(
            @PathVariable String reportName,
            @RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.ok(service.runReport(reportName, params)));
    }
}
