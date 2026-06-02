package com.nubbank.baas.fep.routing;

import com.nubbank.baas.fep.support.StubCardClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for BinResolver — no Spring context needed.
 * BIN normalization invariant: first 8 digits of PAN, zero-padded on the RIGHT to 8 chars.
 * This must match Card's BinService.normalize() exactly.
 */
class BinResolverTest {

    // PAN whose normalized BIN (first 8 digits) is "50600012"
    private static final String KNOWN_PAN        = "5060001234567890";
    private static final String KNOWN_BIN        = "50600012";    // first 8 of KNOWN_PAN
    private static final String UNKNOWN_PAN      = "9999990000000000";
    private static final UUID   PARTNER_ID       = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String SCHEMA_NAME      = "partner_x";

    private StubCardClient stub;
    private BinResolver    resolver;

    @BeforeEach
    void setUp() {
        stub = new StubCardClient();
        stub.register(KNOWN_BIN, new PartnerRoute(PARTNER_ID, SCHEMA_NAME));
        resolver = new BinResolver(stub);
    }

    // ─────────────────── normalization unit tests ────────────────────────────

    @Test
    void bin_16digit_PAN_returns_first8() {
        assertThat(BinResolver.bin(KNOWN_PAN)).isEqualTo("50600012");
    }

    @Test
    void bin_6digit_input_zeroPadsToLength8() {
        // 6 digits → pad 2 zeros on the right: "50600000"
        assertThat(BinResolver.bin("506000")).isEqualTo("50600000");
    }

    @Test
    void bin_13digit_PAN_returns_first8() {
        // "5060001234567" → "50600012"
        assertThat(BinResolver.bin("5060001234567")).isEqualTo("50600012");
    }

    @Test
    void bin_8digit_exact_noChange() {
        assertThat(BinResolver.bin("50600012")).isEqualTo("50600012");
    }

    @Test
    void bin_stripsNonDigits() {
        // spaces or dashes in PAN-like strings should be stripped
        assertThat(BinResolver.bin("5060 0012 3456 7890")).isEqualTo("50600012");
    }

    // ─────────────────── resolve() behavior ──────────────────────────────────

    @Test
    void resolve_knownPAN_returnsRoute() {
        Optional<PartnerRoute> result = resolver.resolve(KNOWN_PAN);
        assertThat(result).isPresent();
        assertThat(result.get().partnerId()).isEqualTo(PARTNER_ID);
        assertThat(result.get().schemaName()).isEqualTo(SCHEMA_NAME);
    }

    @Test
    void resolve_unknownPAN_returnsEmpty() {
        Optional<PartnerRoute> result = resolver.resolve(UNKNOWN_PAN);
        assertThat(result).isEmpty();
    }

    @Test
    void resolve_secondCallForSamePAN_hitsCache_notCardClient() {
        // First call — cache miss → calls stub
        resolver.resolve(KNOWN_PAN);
        assertThat(stub.lookupCount()).isEqualTo(1);

        // Second call — cache hit → stub must NOT be called again
        Optional<PartnerRoute> second = resolver.resolve(KNOWN_PAN);
        assertThat(stub.lookupCount()).isEqualTo(1);
        assertThat(second).isPresent();
    }

    @Test
    void resolve_cacheAlsoCachesNegativeLookup() {
        // Unknown PAN should be cached too (negative caching prevents hammering Card)
        resolver.resolve(UNKNOWN_PAN);
        assertThat(stub.lookupCount()).isEqualTo(1);

        resolver.resolve(UNKNOWN_PAN);
        assertThat(stub.lookupCount()).isEqualTo(1);
    }
}
