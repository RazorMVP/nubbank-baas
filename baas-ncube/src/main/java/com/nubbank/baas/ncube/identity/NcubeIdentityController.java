package com.nubbank.baas.ncube.identity;

import com.nubbank.baas.ncube.common.CbnMediaTypes;
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
@RequestMapping(
    value = "/baas/v1/ncube/identity",
    produces = CbnMediaTypes.CBN_OB_V1_JSON)
public class NcubeIdentityController {

    @PostMapping(value = "/verify-bvn", consumes = CbnMediaTypes.CBN_OB_V1_JSON)
    public ResponseEntity<Map<String, Object>> verifyBvn(
            @Valid @RequestBody BvnVerificationRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        log.debug("BVN verification: {} — Phase 1B stub (Phase 2 calls NIBSS Ncube acmt.023/024)", req.bvn());
        // stubbed BVN — never echoes caller input
        return ResponseEntity.ok(Map.of("data", new VerificationResponse(
            "00000000000", true, "STUB_FIRST", "STUB_LAST",
            null, null, "NIBSS_NCUBE_STUB")));
    }

    @PostMapping(value = "/verify-nin", consumes = CbnMediaTypes.CBN_OB_V1_JSON)
    public ResponseEntity<Map<String, Object>> verifyNin(
            @Valid @RequestBody NinVerificationRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        log.debug("NIN verification: {} — Phase 1B stub", req.nin());
        // stubbed NIN — never echoes caller input
        return ResponseEntity.ok(Map.of("data", new VerificationResponse(
            "00000000000", true, "STUB_FIRST", "STUB_LAST",
            null, null, "NIBSS_NCUBE_STUB")));
    }
}
