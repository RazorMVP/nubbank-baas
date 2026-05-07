package com.nubbank.baas.ncube.common;

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
        // "to" is in NUBAN context list — must mask
        assertThat(maskLine("transfer to 0581000042 amount=1000"))
            .isEqualTo("transfer to 058****042 amount=1000");
    }

    @Test
    void masks_pan_thirteen_to_nineteen_digits() {
        // "card" is in PAN context list — must mask
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

    @Test
    void leaves_unix_millis_timestamp_alone() {
        // 13-digit Unix-ms timestamps must NOT be masked as PAN — no card-context word.
        assertThat(maskLine("ts=1735689600000 elapsed=42ms"))
            .isEqualTo("ts=1735689600000 elapsed=42ms");
    }

    @Test
    void leaves_unix_seconds_timestamp_alone() {
        // 10-digit Unix-s timestamps (JWT iat/exp/nbf) must NOT be masked as NUBAN.
        assertThat(maskLine("iat=1735689600 sub=user-7"))
            .isEqualTo("iat=1735689600 sub=user-7");
    }

    @Test
    void leaves_trace_id_alone() {
        // 19-digit trace IDs (Sleuth/Micrometer Tracing) must NOT be masked as PAN —
        // no card-context word.
        assertThat(maskLine("trace=1735689600000123456 op=verifyBvn"))
            .isEqualTo("trace=1735689600000123456 op=verifyBvn");
    }

    @Test
    void masks_multiple_pii_in_one_line() {
        // Both BVN and NUBAN with context masked in single message.
        assertThat(maskLine("BVN=12345678901 from 0581000042"))
            .isEqualTo("BVN=123****8901 from 058****042");
    }

    private String maskLine(String input) {
        ILoggingEvent ev = mock(ILoggingEvent.class);
        when(ev.getFormattedMessage()).thenReturn(input);
        return sut.convert(ev);
    }
}
