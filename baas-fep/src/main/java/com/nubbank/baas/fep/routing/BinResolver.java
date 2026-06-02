package com.nubbank.baas.fep.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves an incoming PAN to a {@link PartnerRoute} (partnerId + schemaName) via a
 * Caffeine in-process cache backed by {@link CardClient}.
 *
 * <h2>BIN normalization invariant — MUST match Card's {@code BinService.normalize()}</h2>
 * <p>Both FEP and Card operate on the same 8-char normalized BIN:
 * <ol>
 *   <li>Strip any non-digit characters from the PAN.</li>
 *   <li>Take the first 8 digits (or fewer if the PAN has fewer than 8 digits).</li>
 *   <li>Left-align and zero-pad on the <em>right</em> to exactly 8 characters.</li>
 * </ol>
 * Example: PAN {@code "5060001234567890"} → BIN {@code "50600012"}.
 * Example: input {@code "506000"} (only 6 digits) → BIN {@code "50600000"}.
 *
 * <p>Card stores {@code bin_start} / {@code bin_end} in this same 8-char normalized form and
 * performs range matching on it.  If either side diverges, every lookup will miss.
 *
 * <h2>Negative caching</h2>
 * <p>Unknown BINs are also cached ({@link Optional#empty()}) for the TTL period.  This prevents
 * a rogue or mis-configured terminal from hammering the Card service with repeated unknown BINs.
 */
@Component
@RequiredArgsConstructor
public class BinResolver {

    private final CardClient cardClient;

    private final Cache<String, Optional<PartnerRoute>> cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    /**
     * Resolves a full PAN to a {@link PartnerRoute}.
     *
     * <p>The PAN is normalized to a BIN before lookup.  The BIN (not the PAN) is used
     * as the cache key.  The PAN is never stored in the cache.
     *
     * @param pan full card PAN — never logged.
     * @return routing context, or empty if the BIN is not registered in Card.
     */
    public Optional<PartnerRoute> resolve(String pan) {
        String bin = bin(pan);
        // cache.get(key, fn) calls fn only on a cache miss, then stores the result.
        // Caching the Optional (including empty) means a miss is recorded for the TTL
        // and the Card service is not called again within that window.
        return cache.get(bin, cardClient::lookupBin);
    }

    /**
     * Normalizes a PAN (or partial BIN string) to the 8-char canonical BIN used by
     * Card's range-matching table.
     *
     * <p>This is a {@code static} method so tests can verify it in isolation without
     * constructing a full {@link BinResolver}.
     *
     * @param pan PAN or BIN string (digits and optional separators such as spaces/dashes).
     * @return 8-character zero-padded BIN string.
     */
    public static String bin(String pan) {
        if (pan == null) return "00000000";
        String digits = pan.replaceAll("\\D", "");
        // Take at most the first 8 digits; if fewer than 8, use what is there.
        String head = digits.length() >= 8 ? digits.substring(0, 8) : digits;
        // Left-align (head goes to the left) and zero-pad on the right to 8 chars.
        return String.format("%-8s", head).replace(' ', '0');
    }
}
