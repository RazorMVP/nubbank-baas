package com.nubbank.baas.engine.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM-256 field-level encryption for PII columns.
 *
 * Output format: {@code base64(IV(12 bytes) || ciphertext || GCM tag(16 bytes))}.
 * A fresh random IV is generated for every save, so two encryptions of the
 * same plaintext produce different ciphertexts (semantic security).
 *
 * Applied via explicit {@code @Convert(converter = FieldEncryptor.class)} on
 * each PII field — never with {@code autoApply=true}, which would corrupt
 * non-PII string columns.
 *
 * Key derivation: SHA-256 of the configured {@code app.encryption.key}, so
 * any key length works and the AES key is always exactly 256 bits.
 *
 * Because Hibernate 6 honours Spring-managed converters when the converter is
 * an {@code @Component}, {@code @Value} injection works here.
 */
@Component
@Converter
public class FieldEncryptor implements AttributeConverter<String, String> {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;          // 96-bit IV is the GCM standard
    private static final int TAG_BITS = 128;       // 128-bit auth tag
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec aesKey;

    public FieldEncryptor(@Value("${app.encryption.key}") String configuredKey) {
        // Hash the configured key down to a deterministic 256-bit AES key. This
        // lets ENCRYPTION_KEY be any length string while ensuring the AES key
        // is always exactly 32 bytes.
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
            this.aesKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key from app.encryption.key", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbValue) {
        if (dbValue == null) return null;
        try {
            byte[] in = Base64.getDecoder().decode(dbValue);
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[in.length - IV_LEN];
            System.arraycopy(in, 0, iv, 0, IV_LEN);
            System.arraycopy(in, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not a valid base64 → likely a legacy plaintext value pre-encryption rollout.
            // Return it as-is so existing rows don't blow up at load time.
            return dbValue;
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
