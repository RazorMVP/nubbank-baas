package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.AuthorizationDecisionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the FROZEN §2a authorize contract. The matching FEP record
 * ({@code AuthorizationDecision.Request}) lives in a separate Maven module, so a
 * cross-class reflection test is impossible — instead both services assert their
 * record against THIS canonical list (kept identical in
 * {@code docs/contracts/phase1c-interfaces.md} §2a). If you change one, change both.
 */
class AuthorizationContractShapeTest {

    @Test
    void requestComponents_matchCanonicalContract() {
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("partnerId", String.class);
        expected.put("schemaName", String.class);
        expected.put("pan", String.class);
        expected.put("amountMinor", long.class);
        expected.put("currency", String.class);
        expected.put("stan", String.class);
        expected.put("terminalId", String.class);
        expected.put("transmissionDateTime", String.class);

        Map<String, Class<?>> actual = new LinkedHashMap<>();
        for (RecordComponent rc : AuthorizationDecisionRequest.class.getRecordComponents()) {
            actual.put(rc.getName(), rc.getType());
        }
        assertThat(actual).containsExactlyEntriesOf(expected);
    }
}
