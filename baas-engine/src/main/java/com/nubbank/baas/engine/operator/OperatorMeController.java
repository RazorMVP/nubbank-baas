package com.nubbank.baas.engine.operator;

import com.nubbank.baas.engine.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEF-1C-28 — operator self-identity. Any authenticated operator may read their own identity
 * and authorities; there is no permission gate (you can always ask "who am I?"). The tenant
 * chain's auth filter already rejects unauthenticated callers with 401 before routing here.
 */
@RestController
@RequestMapping("/baas/v1/operators")
@RequiredArgsConstructor
public class OperatorMeController {

    private final OperatorIdentityService operatorIdentityService;

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<OperatorMeResponse>> me(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(operatorIdentityService.me(authentication)));
    }
}
