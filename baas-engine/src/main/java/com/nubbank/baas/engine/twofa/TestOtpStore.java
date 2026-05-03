package com.nubbank.baas.engine.twofa;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only OTP plaintext store. Lets integration tests retrieve the
 * generated OTP without going through SMS/email delivery.
 *
 * Annotated {@code @Profile("test")} so the bean does NOT exist in any
 * non-test deployment. Production code receives an {@code Optional.empty()}
 * via constructor injection and the service skips the store entirely.
 *
 * This replaces the previous in-class {@code Map<UUID, String> testOtpStore}
 * that was always allocated and held plaintext OTPs in production memory.
 */
@Component
@Profile("test")
public class TestOtpStore {

    private final Map<UUID, String> store = new ConcurrentHashMap<>();

    public void put(UUID tokenId, String plaintextOtp) {
        store.put(tokenId, plaintextOtp);
    }

    public String get(UUID tokenId) {
        return store.getOrDefault(tokenId, "");
    }

    public void remove(UUID tokenId) {
        store.remove(tokenId);
    }
}
