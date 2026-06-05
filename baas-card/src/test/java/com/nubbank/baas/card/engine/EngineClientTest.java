package com.nubbank.baas.card.engine;

import com.nubbank.baas.card.engine.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 6 — verifies EngineClient is FAIL-CLOSED: when the engine is unreachable,
 * each call returns its safe sentinel rather than throwing into the authorize/reversal path.
 */
class EngineClientTest {

    // Port 1 is reliably refused — simulates an unreachable engine.
    private final EngineClient client = new EngineClient(new RestTemplate(), "http://localhost:1");

    @Test
    void debitFailsClosedToUnreachable() {
        CardDebitResult r = client.cardDebit(new CardDebitRequest(
            "p", "partner_x", UUID.randomUUID(), "k", new BigDecimal("1.00"), "NGN"));
        assertThat(r.outcome()).isEqualTo("UNREACHABLE");
    }

    @Test
    void creditFailsClosedToNotLocated() {
        assertThat(client.cardCredit(new CardCreditRequest("p", "partner_x", "k")).located()).isFalse();
    }

    @Test
    void lookupFailsClosedToNotExists() {
        AccountLookupResult r = client.accountLookup(new AccountLookupRequest("p", "partner_x", UUID.randomUUID()));
        assertThat(r.exists()).isFalse();
        assertThat(r.active()).isFalse();
    }
}
