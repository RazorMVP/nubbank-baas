package com.nubbank.baas.engine.role;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.role.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/roles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_ROLES')")
public class RoleController {

    private final RoleService service;

    @PostMapping
    public ResponseEntity<ApiResponse<Role>> create(@Valid @RequestBody RoleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createRole(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Role>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listRoles()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Role>> update(
            @PathVariable UUID id, @Valid @RequestBody RoleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateRole(id, req)));
    }

    @PutMapping("/{id}/permissions")
    public ResponseEntity<ApiResponse<Role>> updatePermissions(
            @PathVariable UUID id, @Valid @RequestBody UpdatePermissionsRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updatePermissions(id, req)));
    }

    @GetMapping("/permissions")
    public ResponseEntity<ApiResponse<List<Permission>>> allPermissions() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAllPermissions()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
