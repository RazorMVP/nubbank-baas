package com.nubbank.baas.fep.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthorizationDecision.Request#toString()} PAN masking.
 * No Spring context required.
 */
class AuthorizationDecisionTest {

    @Test
    void toString_doesNotContainFullPan() {
        AuthorizationDecision.Request req =
            new AuthorizationDecision.Request("p", "s", "4111111111111111", 100L, "566");

        assertThat(req.toString()).doesNotContain("4111111111111111");
    }

    @Test
    void toString_containsMaskedLast4() {
        AuthorizationDecision.Request req =
            new AuthorizationDecision.Request("p", "s", "4111111111111111", 100L, "566");

        assertThat(req.toString()).contains("****1111");
    }
}
