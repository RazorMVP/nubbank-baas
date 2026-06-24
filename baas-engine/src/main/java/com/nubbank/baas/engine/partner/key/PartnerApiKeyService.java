package com.nubbank.baas.engine.partner.key;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.partner.key.dto.*;
import com.nubbank.baas.engine.role.UserRoleRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartnerApiKeyService {

    private final PartnerApiKeyRepository keyRepo;
    private final PartnerOrganizationRepository orgRepo;
    private final ObjectMapper objectMapper;
    private final UserRoleRepository userRoleRepo;

    // ── caller identity helpers ──────────────────────────────────────────────

    private UUID callerUserId() {
        return UUID.fromString(
            (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    private Set<String> callerAuthorities() {
        return SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(Collectors.toSet());
    }

    private boolean callerIsSuperuser() {
        return userRoleRepo.existsSuperuserRoleByUserId(callerUserId());
    }

    // ── service methods ──────────────────────────────────────────────────────

    @Transactional
    public IssuedApiKeyResponse issue(IssueApiKeyRequest req) {
        PartnerContext ctx = PartnerContext.get();
        PartnerOrganization org = orgRepo.findById(UUID.fromString(ctx.partnerId()))
            .orElseThrow(() -> BaasException.notFound("ORG_NOT_FOUND", "Org not found"));

        byte[] rand = new byte[32];
        new SecureRandom().nextBytes(rand);
        String raw = "cba_" + Base64.getUrlEncoder().withoutPadding().encodeToString(rand);

        List<String> scopes = req.scopes() == null ? List.of() : req.scopes();

        // FIX C2 — non-superuser callers cannot issue wildcard keys or scope beyond their own authority
        if (!callerIsSuperuser()) {
            Set<String> mine = callerAuthorities();
            for (String s : scopes) {
                if ("*".equals(s))
                    throw BaasException.forbidden("PRIVILEGE_ESCALATION",
                        "Only a superuser can issue a wildcard (full-authority) API key");
                if (!mine.contains(s))
                    throw BaasException.forbidden("PRIVILEGE_ESCALATION",
                        "Cannot scope a key beyond your own authority: " + s);
            }
        }

        String scopesJson;
        try {
            scopesJson = objectMapper.writeValueAsString(scopes);
        } catch (Exception e) {
            throw BaasException.badRequest("BAD_SCOPES", "Invalid scopes");
        }

        PartnerApiKey k = keyRepo.save(PartnerApiKey.builder()
            .organization(org)
            .keyHash(sha256Hex(raw))
            .keyPrefix(raw.substring(0, 12))
            .name(req.name())
            .scopes(scopesJson)
            .tier(org.getTier())
            .environment(org.getEnvironment())
            .active(true)
            .build());

        return new IssuedApiKeyResponse(k.getId(), k.getKeyPrefix(), raw);
    }

    /**
     * Lowercase hex SHA-256 of the raw key — must match {@code PartnerContextFilter.sha256Hex}
     * exactly so the issued key authenticates on subsequent requests.
     */
    private String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
