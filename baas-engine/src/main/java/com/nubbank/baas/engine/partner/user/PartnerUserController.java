package com.nubbank.baas.engine.partner.user;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.partner.user.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/baas/v1/partner-users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_PARTNER_USERS')")
public class PartnerUserController {

    private final PartnerUserService service;

    @PostMapping
    public ResponseEntity<ApiResponse<PartnerUserResponse>> create(
            @Valid @RequestBody CreatePartnerUserRequest req) {
        var u = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.get(u.getId())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PartnerUserResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.list()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PartnerUserResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<ApiResponse<PartnerUserResponse>> roles(
            @PathVariable UUID id, @Valid @RequestBody UpdateUserRolesRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.replaceRoles(id, req)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        service.setActive(id, false);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable UUID id) {
        service.setActive(id, true);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
