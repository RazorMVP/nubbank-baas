package com.nubbank.baas.card.card;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link PanHasher} — no Spring context.
 *
 * <p>Covers the fail-fast key validation guard and locks in the deterministic
 * HMAC-SHA256 hashing contract that Task 6's authorize flow depends on.
 */
class PanHasherTest {

    // 32-char key, mirrors the real configured length; comfortably above the 16-char minimum.
    private static final String VALID_KEY = "test-encryption-key-exactly-32c!";

    @Test
    void constructor_rejectsNullKey() {
        assertThatThrownBy(() -> new PanHasher(null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsBlankKey() {
        assertThatThrownBy(() -> new PanHasher(""))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PanHasher("   "))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsTooShortKey() {
        // 15 chars — one below the minimum.
        assertThatThrownBy(() -> new PanHasher("123456789012345"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_doesNotLeakKeyValueInMessage() {
        String shortKey = "short-secret-12";
        assertThatThrownBy(() -> new PanHasher(shortKey))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(shortKey);
    }

    @Test
    void constructor_acceptsValidKey() {
        PanHasher hasher = new PanHasher(VALID_KEY);
        assertThat(hasher).isNotNull();
    }

    @Test
    void hash_returns64CharLowercaseHex() {
        PanHasher hasher = new PanHasher(VALID_KEY);

        String result = hasher.hash("4567123412341234");

        assertThat(result).hasSize(64);
        assertThat(result).matches("[0-9a-f]{64}");
    }

    @Test
    void hash_isDeterministic_samePanYieldsSameHash() {
        PanHasher hasher = new PanHasher(VALID_KEY);

        String first = hasher.hash("4567123412341234");
        String second = hasher.hash("4567123412341234");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void hash_differentPansYieldDifferentHashes() {
        PanHasher hasher = new PanHasher(VALID_KEY);

        String a = hasher.hash("4567123412341234");
        String b = hasher.hash("4567123412341235");

        assertThat(a).isNotEqualTo(b);
    }
}
