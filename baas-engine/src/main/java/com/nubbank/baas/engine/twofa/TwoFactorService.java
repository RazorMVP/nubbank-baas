package com.nubbank.baas.engine.twofa;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.twofa.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private final TwoFactorTokenRepository repo;
    private final TwoFactorTokenWriter writer;
    /**
     * Optional test-only plaintext OTP store. Empty in production (the
     * {@link TestOtpStore} bean is gated by {@code @Profile("test")} so it
     * is never autowired outside tests). Calls to the store are no-ops
     * when the Optional is empty.
     */
    private final java.util.Optional<TestOtpStore> testOtpStore;

    /**
     * HMAC-SHA256 key for hashing OTP tokens. Must be set via env var or
     * application.yml — there is no default. Production deployments fail
     * to start if this is missing, which is the correct behaviour.
     */
    @Value("${app.encryption.key}")
    private String hmacKey;

    @Transactional
    public Map<String, Object> generateOtp(GenerateOtpRequest req) {
        requireContext();
        String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        String hash = hmacSha256(otp, hmacKey);

        TwoFactorToken token = repo.save(TwoFactorToken.builder()
            .userId(req.userId())
            .tokenHash(hash)
            .deliveryMethod(req.deliveryMethod())
            .recipient(req.recipient())
            .expiresAt(Instant.now().plusSeconds(600))
            .build());

        // Test-only side channel: bean is absent in production, so this is a no-op.
        testOtpStore.ifPresent(s -> s.put(token.getId(), otp));

        return Map.of("tokenId", token.getId(), "expiresAt", token.getExpiresAt(),
            "deliveryMethod", token.getDeliveryMethod());
    }

    /** Maximum allowed failed verification attempts before token is locked. */
    private static final int MAX_ATTEMPTS = 5;

    @Transactional
    public Map<String, Object> verifyOtp(VerifyOtpRequest req) {
        requireContext();
        TwoFactorToken token = repo.findById(req.tokenId())
            .orElseThrow(() -> BaasException.notFound("TOKEN_NOT_FOUND", "OTP token not found"));

        if (token.isVerified())
            throw BaasException.badRequest("TOKEN_ALREADY_USED", "This OTP has already been used");
        if (token.isLocked())
            throw BaasException.badRequest("TOKEN_LOCKED",
                "This OTP token is locked due to too many failed attempts. Request a new code.");
        if (Instant.now().isAfter(token.getExpiresAt()))
            throw BaasException.badRequest("TOKEN_EXPIRED", "OTP has expired");

        // Constant-time comparison to avoid timing-side-channel leakage
        String hash = hmacSha256(req.otp(), hmacKey);
        if (!constantTimeEquals(hash, token.getTokenHash())) {
            // Record the failed attempt in a SEPARATE transaction (REQUIRES_NEW)
            // so the increment survives the rollback caused by throwing below.
            // Without this, the JPA rollback resets failedAttempts to 0 and the
            // brute-force lockout never engages.
            TwoFactorToken updated = writer.recordFailedAttempt(req.tokenId(), MAX_ATTEMPTS);
            if (updated.isLocked()) {
                throw BaasException.badRequest("TOKEN_LOCKED",
                    "Too many failed attempts — token is now locked. Request a new code.");
            }
            int remaining = MAX_ATTEMPTS - updated.getFailedAttempts();
            throw BaasException.badRequest("INVALID_OTP",
                "The OTP provided is incorrect. " + remaining + " attempts remaining.");
        }

        token.setVerified(true);
        repo.save(token);
        testOtpStore.ifPresent(s -> s.remove(req.tokenId()));

        return Map.of("verified", true, "userId", token.getUserId());
    }

    /** Constant-time string comparison — defends against timing attacks on the hash check. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    /**
     * Test helper — retrieves the plaintext OTP for the given token ID. Returns
     * an empty string in production where {@link TestOtpStore} is not on the
     * classpath (the bean is gated by {@code @Profile("test")}).
     */
    public String getPlaintextOtpForTest(UUID tokenId) {
        return testOtpStore.map(s -> s.get(tokenId)).orElse("");
    }

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
