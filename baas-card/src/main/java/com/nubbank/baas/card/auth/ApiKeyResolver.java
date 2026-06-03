package com.nubbank.baas.card.auth;

import com.nubbank.baas.card.partner.PartnerApiKeyRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Resolves a raw API key into a {@link PartnerContext}.
 *
 * SHA-256 (lowercase hex) the raw key, look it up via
 * {@code findByKeyHashAndActiveTrue}, build the PartnerContext from the joined
 * {@code partner_organizations} row (authMode {@code "API_KEY"}), and stamp
 * {@code last_used_at}. Any failure is swallowed — PartnerContext is simply left
 * null, and {@code AuthEnforcementFilter} returns 401. (Extracted from engine's
 * {@code PartnerContextFilter.resolveApiKey}.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyResolver {

    private final PartnerApiKeyRepository apiKeyRepo;

    public void resolve(String rawKey) {
        try {
            String keyHash = sha256Hex(rawKey);
            apiKeyRepo.findByKeyHashAndActiveTrue(keyHash).ifPresent(key -> {
                PartnerContext ctx = new PartnerContext(
                    key.getOrganization().getId().toString(),
                    key.getOrganization().getSchemaName(),
                    key.getTier().name(),
                    key.getEnvironment().name(),
                    "API_KEY",
                    null
                );
                PartnerContext.set(ctx);
                // Update last_used_at — the repository method is @Transactional
                apiKeyRepo.updateLastUsed(key.getId(), Instant.now());
            });
        } catch (Exception ex) {
            log.debug("API key resolution failed: {}", ex.getMessage());
            // Do not expose the failure — just leave PartnerContext null
        }
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
