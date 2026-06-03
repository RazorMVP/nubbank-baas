package com.nubbank.baas.card.common;

import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ISO 4217 numeric-code → minor-unit exponent, derived from the JDK at startup.
 *
 * <p>Replaces the previous hardcoded {@code movePointLeft(2)} in
 * {@code AuthorizationDecisionService}, which silently mis-scaled 0-decimal
 * (JPY) and 3-decimal (KWD/BHD/TND) currencies.
 *
 * <p>The table is built from {@link Currency#getAvailableCurrencies()} so it tracks
 * the JDK's maintained ISO 4217 data — no hand-maintained list to drift. Keys are the
 * zero-padded 3-digit numeric code (e.g. {@code "566"} for NGN); values are
 * {@link Currency#getDefaultFractionDigits()}. Pseudo-currencies (numeric code {@code <= 0}
 * or fraction digits {@code < 0}, e.g. XXX) are excluded so an unknown/invalid code
 * resolves to {@link Optional#empty()} and the caller can decline (RC 12).
 */
@Component
public class CurrencyMinorUnits {

    private final Map<String, Integer> exponentByNumeric;

    public CurrencyMinorUnits() {
        Map<String, Integer> map = new HashMap<>();
        for (Currency c : Currency.getAvailableCurrencies()) {
            int numeric = c.getNumericCode();
            int digits = c.getDefaultFractionDigits();
            if (numeric > 0 && digits >= 0) {
                map.put(String.format("%03d", numeric), digits);
            }
        }
        this.exponentByNumeric = Map.copyOf(map);
    }

    /**
     * @param numericCode ISO 4217 numeric code as it appears in DE49 (e.g. {@code "566"}).
     * @return the minor-unit exponent, or empty if the code is null/blank/unknown.
     */
    public Optional<Integer> exponentFor(String numericCode) {
        if (numericCode == null || numericCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(exponentByNumeric.get(numericCode));
    }
}
