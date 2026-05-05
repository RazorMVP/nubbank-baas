package com.nubbank.baas.engine.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PiiMaskingConverterTest {

    private final PiiMaskingConverter sut = new PiiMaskingConverter();

    @Test
    void masks_bvn_eleven_digits() {
        assertThat(maskLine("BVN verification: 12345678901 succeeded"))
            .isEqualTo("BVN verification: 123****8901 succeeded");
    }

    @Test
    void masks_nin_eleven_digits() {
        assertThat(maskLine("NIN=98765432109 verified"))
            .isEqualTo("NIN=987****2109 verified");
    }

    @Test
    void masks_nuban_ten_digits() {
        assertThat(maskLine("transfer to 0581000042 amount=1000"))
            .isEqualTo("transfer to 058****042 amount=1000");
    }

    @Test
    void masks_pan_thirteen_to_nineteen_digits() {
        assertThat(maskLine("card 4123456789012345 charged"))
            .isEqualTo("card 4123********2345 charged");
    }

    @Test
    void leaves_short_numbers_alone() {
        // 4 to 9 digits not masked — likely amounts/IDs, not PII
        assertThat(maskLine("amount=99999 retries=3"))
            .isEqualTo("amount=99999 retries=3");
    }

    @Test
    void leaves_iso_dates_alone() {
        assertThat(maskLine("Started at 2026-05-04T10:23:45Z"))
            .isEqualTo("Started at 2026-05-04T10:23:45Z");
    }

    private String maskLine(String input) {
        ILoggingEvent ev = mock(ILoggingEvent.class);
        when(ev.getFormattedMessage()).thenReturn(input);
        return sut.convert(ev);
    }
}
