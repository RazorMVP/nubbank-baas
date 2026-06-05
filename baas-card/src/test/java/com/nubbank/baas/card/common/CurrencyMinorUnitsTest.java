package com.nubbank.baas.card.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyMinorUnitsTest {

    private final CurrencyMinorUnits units = new CurrencyMinorUnits();

    @Test
    void ngn_isTwoDecimals() {
        assertThat(units.exponentFor("566")).contains(2);   // NGN
    }

    @Test
    void usd_isTwoDecimals() {
        assertThat(units.exponentFor("840")).contains(2);   // USD
    }

    @Test
    void jpy_isZeroDecimals() {
        assertThat(units.exponentFor("392")).contains(0);   // JPY
    }

    @Test
    void kwd_isThreeDecimals() {
        assertThat(units.exponentFor("414")).contains(3);   // KWD (Kuwaiti dinar)
    }

    @Test
    void unknownNumericCode_isEmpty() {
        assertThat(units.exponentFor("999")).isEmpty();   // XXX (no currency) — excluded: fraction digits = -1
    }

    @Test
    void nullOrBlank_isEmpty() {
        assertThat(units.exponentFor(null)).isEmpty();
        assertThat(units.exponentFor("")).isEmpty();
    }

    @Test
    void alphaFor_knownNumeric_returnsIsoAlpha() {
        assertThat(units.alphaFor("566")).contains("NGN");
        assertThat(units.alphaFor("840")).contains("USD");
        assertThat(units.alphaFor("392")).contains("JPY");
    }

    @Test
    void alphaFor_unknownOrBlank_isEmpty() {
        assertThat(units.alphaFor("999")).isEmpty();   // XXX excluded
        assertThat(units.alphaFor("000")).isEmpty();
        assertThat(units.alphaFor(null)).isEmpty();
        assertThat(units.alphaFor("")).isEmpty();
    }
}
