package com.nubbank.baas.engine.customer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Deterministic blind index over encrypted customer names. For each name word, emits
 * HMAC-SHA256 of every prefix (len 2..12) so a prefix query token matches a stored token.
 * Names stay AES-encrypted; only these one-way hashes are stored in {@code name_search_tokens}.
 */
@Component
public class NameTokenizer {

    private static final String HMAC = "HmacSHA256";
    private static final int MIN_PREFIX = 2;
    private static final int MAX_PREFIX = 12;
    private static final int MAX_WORDS = 6;

    private final byte[] key;

    public NameTokenizer(@Value("${app.encryption.key}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("app.encryption.key must be configured for name tokenization");
        }
        this.key = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    /** All stored prefix tokens for a customer's name. */
    public List<String> tokensForName(String firstName, String lastName) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String word : words(firstName, lastName)) {
            int max = Math.min(word.length(), MAX_PREFIX);
            for (int len = MIN_PREFIX; len <= max; len++) {
                tokens.add(hmac(word.substring(0, len)));
            }
        }
        return new ArrayList<>(tokens);
    }

    /** Token for a single search word (matched against the stored set). */
    public String queryToken(String word) {
        return hmac(normalize(word));
    }

    private List<String> words(String firstName, String lastName) {
        List<String> out = new ArrayList<>();
        for (String part : new String[]{firstName, lastName}) {
            if (part == null) continue;
            for (String w : normalize(part).split("\\s+")) {
                if (w.length() >= MIN_PREFIX && out.size() < MAX_WORDS) out.add(w);
            }
        }
        return out;
    }

    private static String normalize(String s) {
        String lower = s.toLowerCase(Locale.ROOT).trim();
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private String hmac(String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Name tokenization failed", e);
        }
    }
}
