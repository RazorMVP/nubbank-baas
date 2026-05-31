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
        // IF_CHAR (fixed) fields may be space-padded to their declared length (8 chars)
        assertThat(unpacked.getString(IsoField.TERMINAL_ID).trim()).isEqualTo("TERM0001");
        assertThat(unpacked.getString(IsoField.CURRENCY)).isEqualTo("566");
    }
}
