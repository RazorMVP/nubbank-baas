package com.nubbank.baas.fep.iso;

import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test — no Spring context needed.
 * Verifies that IsoMessageFactory can pack an ISO 8583 0100 message
 * and unpack the bytes back to identical field values.
 */
class IsoMessageFactoryTest {

    @Test
    void packAndUnpack_0100_roundTripsAllFields() throws Exception {
        IsoMessageFactory factory = new IsoMessageFactory();

        // ── Build a 0100 Authorization Request ──────────────────────────
        ISOMsg request = factory.create("0100");
        request.set(IsoField.PAN,          "5060001234567890");   // DE2  – LLNUM
        request.set(IsoField.PROC_CODE,    "000000");             // DE3
        request.set(IsoField.AMOUNT,       "000000005000");       // DE4
        request.set(IsoField.STAN,         "000001");             // DE11
        request.set(IsoField.TERMINAL_ID,  "TERM0001");           // DE41
        request.set(IsoField.CURRENCY,     "566");                // DE49

        // ── Pack → wire bytes ────────────────────────────────────────────
        byte[] bytes = factory.pack(request);
        assertThat(bytes).isNotEmpty();

        // ── Unpack from bytes ────────────────────────────────────────────
        ISOMsg unpacked = factory.unpack(bytes);

        // ── Assert round-trip equality ───────────────────────────────────
        assertThat(unpacked.getMTI()).isEqualTo("0100");
        assertThat(unpacked.getString(IsoField.PAN)).isEqualTo("5060001234567890");
        assertThat(unpacked.getString(IsoField.PROC_CODE)).isEqualTo("000000");
        assertThat(unpacked.getString(IsoField.AMOUNT)).isEqualTo("000000005000");
        assertThat(unpacked.getString(IsoField.STAN)).isEqualTo("000001");
        // TERM0001 fills the 8-char IF_CHAR field exactly — no padding occurs here;
        // callers must trim shorter values (see ifCharFieldIsPaddedToFullLength below).
        assertThat(unpacked.getString(IsoField.TERMINAL_ID).trim()).isEqualTo("TERM0001");
        assertThat(unpacked.getString(IsoField.CURRENCY)).isEqualTo("566");
    }

    /**
     * Fix 2: verifies that jPOS right-space-pads an IF_CHAR field when the supplied
     * value is shorter than the declared field length.
     * DE42 (MERCHANT_ID) is IF_CHAR length 15; "MERCH_A" (7 chars) must come back
     * padded to 15 on the wire, and trim() must recover the original value.
     */
    @Test
    void ifCharFieldIsPaddedToFullLength() throws Exception {
        IsoMessageFactory factory = new IsoMessageFactory();

        ISOMsg request = factory.create("0100");
        request.set(IsoField.MERCHANT_ID, "MERCH_A");   // 7 chars in a 15-char IF_CHAR field

        byte[] bytes = factory.pack(request);
        ISOMsg unpacked = factory.unpack(bytes);

        // Raw wire value: right-space-padded to the full declared length of 15.
        assertThat(unpacked.getString(IsoField.MERCHANT_ID))
                .isEqualTo("MERCH_A        ");   // "MERCH_A" + 8 spaces = 15 chars

        // Callers that need the logical value must trim.
        assertThat(unpacked.getString(IsoField.MERCHANT_ID).trim())
                .isEqualTo("MERCH_A");
    }

    /**
     * Fix 3: verifies that a PAN shorter than the IFA_LLNUM maximum (19) round-trips
     * without padding or truncation.  The 2-digit LL length prefix must correctly
     * encode 13, and the unpacked value must be exactly the original 13 digits.
     */
    @Test
    void shorterLlnumPanRoundTrips() throws Exception {
        IsoMessageFactory factory = new IsoMessageFactory();

        ISOMsg request = factory.create("0100");
        request.set(IsoField.PAN, "5060001234567");   // 13-digit PAN (max is 19)

        byte[] bytes = factory.pack(request);
        ISOMsg unpacked = factory.unpack(bytes);

        // No padding, no truncation — the LL prefix encodes 13 exactly.
        assertThat(unpacked.getString(IsoField.PAN)).isEqualTo("5060001234567");
    }
}
