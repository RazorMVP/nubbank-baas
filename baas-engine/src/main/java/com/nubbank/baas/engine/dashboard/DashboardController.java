package com.nubbank.baas.engine.dashboard;

import com.nubbank.baas.engine.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEF-1C-29 — operations-console summary. Any authenticated operator sees their tenant's
 * top-line tiles (the data is already partner-schema isolated), so the gate is just
 * {@code isAuthenticated()} rather than a specific permission.
 */
@RestController
@RequestMapping("/baas/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> summary() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.summary()));
    }
}
