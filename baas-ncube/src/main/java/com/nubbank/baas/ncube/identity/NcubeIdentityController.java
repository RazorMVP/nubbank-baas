package com.nubbank.baas.ncube.identity;

import com.nubbank.baas.ncube.identity.dto.BvnVerificationRequest;
import com.nubbank.baas.ncube.identity.dto.NinVerificationRequest;
import com.nubbank.baas.ncube.identity.dto.VerificationResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * BVN and NIN identity verification endpoints.
 * Phase 1B: validates 11-digit format and returns stub "verified" response.
 * Phase 2: calls NIBSS Ncube identity rails via acmt.023/024 for live verification.
 * CBN requirement: BVN/NIN verification mandatory before account opening and credit transfer.
 */
@Slf4j
@RestController
@RequestMapping("/baas/v1/ncube/identity")
public class NcubeIdentityController {

    @PostMapping("/verify-bvn")
    public ResponseEntity<Map<String, Object>> verifyBvn(
            @Valid @RequestBody BvnVerificationRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        log.debug("BVN verification: {} — Phase 1B stub (Phase 2 calls NIBSS Ncube acmt.023/024)", req.bvn());
        return ResponseEntity.ok(Map.of("data", new VerificationResponse(
            req.bvn(), true, "STUB_FIRST", "STUB_LAST",
            null, null, "NIBSS_NCUBE_STUB")));
    }

    @PostMapping("/verify-nin")
    public ResponseEntity<Map<String, Object>> verifyNin(
            @Valid @RequestBody NinVerificationRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        log.debug("NIN verification: {} — Phase 1B stub", req.nin());
        return ResponseEntity.ok(Map.of("data", new VerificationResponse(
            req.nin(), true, "STUB_FIRST", "STUB_LAST",
            null, null, "NIBSS_NCUBE_STUB")));
    }
}
