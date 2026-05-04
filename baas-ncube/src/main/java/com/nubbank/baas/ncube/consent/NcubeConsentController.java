package com.nubbank.baas.ncube.consent;

import com.nubbank.baas.ncube.common.*;
import com.nubbank.baas.ncube.consent.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping(
    value = "/baas/v1/ncube/consents",
    produces = CbnMediaTypes.CBN_OB_V1_JSON)
@RequiredArgsConstructor
public class NcubeConsentController {

    private final NcubeConsentClient consentClient;
    private static final String BASE = "https://api.nubbank.com/baas/v1/ncube/consents";

    @GetMapping
    public CbnApiResponse<Map<String, Object>> getConsents(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        List<CbnConsentItem> consents = consentClient.getConsents(auth).stream()
            .map(this::toCbn).toList();
        return new CbnApiResponse<>(Map.of("Consent", consents), new CbnLinks(BASE), new CbnMeta(1));
    }

    @PostMapping(consumes = CbnMediaTypes.CBN_OB_V1_JSON)
    public ResponseEntity<CbnApiResponse<Map<String, Object>>> createConsent(
            @RequestBody CbnConsentRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        NubBankConsentDto created = consentClient.createConsent(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CbnApiResponse<>(
            Map.of("Consent", toCbn(created)),
            new CbnLinks(BASE + "/" + created.id()),
            new CbnMeta(1)));
    }

    @DeleteMapping("/{consentId}")
    public ResponseEntity<Void> revokeConsent(
            @PathVariable String consentId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        consentClient.revokeConsent(auth, consentId);
        return ResponseEntity.noContent().build();
    }

    private CbnConsentItem toCbn(NubBankConsentDto dto) {
        String status = switch (dto.status()) {
            case "AWAITING_AUTHORISATION" -> "AwaitingAuthorisation";
            case "AUTHORISED"             -> "Authorised";
            case "REVOKED"                -> "Revoked";
            default                       -> dto.status();
        };
        return new CbnConsentItem(
            dto.id(), dto.createdAt(), status, dto.createdAt(),
            dto.scopes(), dto.expiryDate(), dto.tppClientId(), "NubBank Partner");
    }
}
