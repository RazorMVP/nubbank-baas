package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.makerchecker.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/maker-checker")
@RequiredArgsConstructor
public class MakerCheckerTaskController {

    private final MakerCheckerTaskService service;

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String type) {
        List<TaskResponse> tasks = service.list(status, type).stream().map(TaskResponse::of).toList();
        return ResponseEntity.ok(ApiResponse.ok(tasks));
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<ApiResponse<TaskDetailResponse>> get(@PathVariable UUID id) {
        MakerCheckerTask task = service.get(id);
        String invalidReason = service.dryRunInvalidReason(task);
        return ResponseEntity.ok(ApiResponse.ok(TaskDetailResponse.of(task, invalidReason)));
    }

    // No static @PreAuthorize: the per-command APPROVE_* authority is checked dynamically in the service.
    @PostMapping("/tasks/{id}/approve")
    public ResponseEntity<ApiResponse<TaskResponse>> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(TaskResponse.of(service.approve(id))));
    }

    @PostMapping("/tasks/{id}/reject")
    public ResponseEntity<ApiResponse<TaskResponse>> reject(@PathVariable UUID id,
            @RequestBody(required = false) RejectRequest req) {
        String reason = req == null ? null : req.reason();
        return ResponseEntity.ok(ApiResponse.ok(TaskResponse.of(service.reject(id, reason))));
    }

    @PostMapping("/tasks/{id}/withdraw")
    public ResponseEntity<ApiResponse<TaskResponse>> withdraw(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(TaskResponse.of(service.withdraw(id))));
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('MANAGE_MAKER_CHECKER')")
    public ResponseEntity<ApiResponse<List<ConfigResponse>>> getConfig() {
        List<ConfigResponse> cfgs = service.listConfig().stream().map(ConfigResponse::of).toList();
        return ResponseEntity.ok(ApiResponse.ok(cfgs));
    }

    @PutMapping("/config")
    @PreAuthorize("hasAuthority('MANAGE_MAKER_CHECKER')")
    public ResponseEntity<ApiResponse<ConfigResponse>> updateConfig(@Valid @RequestBody ConfigUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(ConfigResponse.of(service.updateConfig(req.commandType(), req.enabled()))));
    }
}
