package com.nubbank.baas.engine.audit;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/audits")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository repo;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<AuditLog>>> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");

        PageRequest pr = PageRequest.of(page, size);
        Page<AuditLog> result;
        if (entityType != null && entityId != null)
            result = repo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType.toUpperCase(), entityId, pr);
        else if (entityType != null)
            result = repo.findByEntityTypeOrderByCreatedAtDesc(entityType.toUpperCase(), pr);
        else
            result = repo.findAllByOrderByCreatedAtDesc(pr);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
