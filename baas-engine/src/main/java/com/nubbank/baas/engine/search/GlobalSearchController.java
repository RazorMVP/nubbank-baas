package com.nubbank.baas.engine.search;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.common.TenantJdbcTemplate;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/baas/v1/search")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final TenantJdbcTemplate tenantJdbc;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(
            @RequestParam String query,
            @RequestParam(required = false) String resource) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");

        if (query == null || query.trim().length() < 2)
            throw BaasException.badRequest("QUERY_TOO_SHORT",
                "Search query must be at least 2 characters");

        String like = "%" + query.trim().toLowerCase() + "%";
        List<Map<String, Object>> results = new ArrayList<>();

        String res = resource != null ? resource.toUpperCase() : "ALL";
        try {
            if ("ALL".equals(res) || "CLIENTS".equals(res)) {
                results.addAll(tenantJdbc.queryForList(
                    "SELECT id::text AS \"entityId\", 'CUSTOMER' AS \"entityType\", " +
                    "external_reference AS \"entityAccountNo\", " +
                    "kyc_status AS \"entityStatus\" " +
                    "FROM customers WHERE external_reference ILIKE ? LIMIT 20", like));
            }
            if ("ALL".equals(res) || "LOANS".equals(res)) {
                results.addAll(tenantJdbc.queryForList(
                    "SELECT id::text AS \"entityId\", 'LOAN' AS \"entityType\", " +
                    "loan_account_number AS \"entityAccountNo\", " +
                    "status AS \"entityStatus\" " +
                    "FROM loans WHERE loan_account_number ILIKE ? LIMIT 20", like));
            }
            if ("ALL".equals(res) || "ACCOUNTS".equals(res)) {
                results.addAll(tenantJdbc.queryForList(
                    "SELECT id::text AS \"entityId\", 'ACCOUNT' AS \"entityType\", " +
                    "account_number AS \"entityAccountNo\", " +
                    "status AS \"entityStatus\" " +
                    "FROM accounts WHERE account_number ILIKE ? LIMIT 20", like));
            }
        } catch (Exception e) {
            // Tables may have no data yet in fresh schemas — return whatever we have
        }

        return ResponseEntity.ok(ApiResponse.ok(results));
    }
}
