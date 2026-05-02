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
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private final TwoFactorTokenRepository repo;

    @Value("${app.encryption.key:nubbank-baas-dev-enc-key-32chars!}")
    private String hmacKey;

    private final Map<UUID, String> testOtpStore = new ConcurrentHashMap<>();

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

        testOtpStore.put(token.getId(), otp);

        return Map.of("tokenId", token.getId(), "expiresAt", token.getExpiresAt(),
            "deliveryMethod", token.getDeliveryMethod());
    }

    @Transactional
    public Map<String, Object> verifyOtp(VerifyOtpRequest req) {
        requireContext();
        TwoFactorToken token = repo.findById(req.tokenId())
            .orElseThrow(() -> BaasException.notFound("TOKEN_NOT_FOUND", "OTP token not found"));

        if (token.isVerified())
            throw BaasException.badRequest("TOKEN_ALREADY_USED", "This OTP has already been used");
        if (Instant.now().isAfter(token.getExpiresAt()))
            throw BaasException.badRequest("TOKEN_EXPIRED", "OTP has expired");

        String hash = hmacSha256(req.otp(), hmacKey);
        if (!hash.equals(token.getTokenHash()))
            throw BaasException.badRequest("INVALID_OTP", "The OTP provided is incorrect");

        token.setVerified(true);
        repo.save(token);
        testOtpStore.remove(req.tokenId());

        return Map.of("verified", true, "userId", token.getUserId());
    }

    /** Test helper — retrieves the plaintext OTP for the given token ID. Never call in production. */
    public String getPlaintextOtpForTest(UUID tokenId) {
        return testOtpStore.getOrDefault(tokenId, "");
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
