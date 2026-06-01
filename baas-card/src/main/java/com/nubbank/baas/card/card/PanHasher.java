package com.nubbank.baas.card.card;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic PAN fingerprint for card lookup.
 *
 * <p>AES-GCM (via {@code FieldEncryptor}) is non-deterministic — a fresh random IV
 * per save means the same PAN encrypts to a different ciphertext every time, so it
 * cannot be queried. {@code pan_hash} is the deterministic companion column:
 * {@code HMAC-SHA256(key = app.encryption.key bytes, message = full PAN)} as
 * lowercase hex. It is UNIQUE within a tenant schema (prevents duplicate cards) and
 * is how Task 6's authorize resolves a card from a PAN off the wire via
 * {@code cardRepository.findByPanHash(panHasher.hash(pan))}.
 *
 * <p>HMAC (keyed) rather than a bare digest so the fingerprint is not brute-forceable
 * by an attacker who only has the hash column — recovering a PAN would require the
 * secret key as well.
 *
 * <p>SECURITY: never log the PAN passed in, nor the hash returned.
 */
@Component
public class PanHasher {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] keyBytes;

    public PanHasher(@Value("${app.encryption.key}") String configuredKey) {
        this.keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param pan the full Primary Account Number
     * @return lowercase hex HMAC-SHA256 of the PAN (64 chars)
     */
    public String hash(String pan) {
        if (pan == null) {
            throw new IllegalArgumentException("pan must not be null");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGO));
            byte[] digest = mac.doFinal(pan.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // Never include the PAN in the message.
            throw new IllegalStateException("Failed to compute PAN hash", e);
        }
    }
}
