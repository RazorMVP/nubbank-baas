package com.nubbank.baas.engine.group;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.group.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class GroupController {

    private final GroupService service;

    @PostMapping("/baas/v1/groups")
    public ResponseEntity<ApiResponse<Group>> createGroup(@Valid @RequestBody GroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createGroup(req)));
    }

    @GetMapping("/baas/v1/groups")
    public ResponseEntity<ApiResponse<Page<Group>>> listGroups(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listGroups(page, size)));
    }

    @PostMapping("/baas/v1/groups/{id}")
    public ResponseEntity<ApiResponse<Group>> command(
            @PathVariable UUID id, @RequestParam String command) {
        return ResponseEntity.ok(ApiResponse.ok(service.executeCommand(id, command)));
    }

    @PostMapping("/baas/v1/groups/{groupId}/members/{customerId}")
    public ResponseEntity<ApiResponse<GroupMember>> addMember(
            @PathVariable UUID groupId, @PathVariable UUID customerId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addMember(groupId, customerId)));
    }

    @DeleteMapping("/baas/v1/groups/{groupId}/members/{customerId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID groupId, @PathVariable UUID customerId) {
        service.removeMember(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/baas/v1/groups/{groupId}/members")
    public ResponseEntity<ApiResponse<List<GroupMember>>> listMembers(@PathVariable UUID groupId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listMembers(groupId)));
    }

    @PostMapping("/baas/v1/centers")
    public ResponseEntity<ApiResponse<Center>> createCenter(@Valid @RequestBody CenterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createCenter(req)));
    }

    @GetMapping("/baas/v1/centers")
    public ResponseEntity<ApiResponse<List<Center>>> listCenters() {
        return ResponseEntity.ok(ApiResponse.ok(service.listCenters()));
    }

    @PostMapping("/baas/v1/centers/{id}/activate")
    public ResponseEntity<ApiResponse<Center>> activateCenter(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.activateCenter(id)));
    }

    @PostMapping("/baas/v1/centers/{centerId}/groups/{groupId}")
    public ResponseEntity<ApiResponse<CenterGroup>> addGroupToCenter(
            @PathVariable UUID centerId, @PathVariable UUID groupId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addGroupToCenter(centerId, groupId)));
    }
}
