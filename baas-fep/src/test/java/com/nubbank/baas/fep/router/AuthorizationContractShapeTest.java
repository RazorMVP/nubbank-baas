package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.routing.AuthorizationDecision;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the FROZEN §2a authorize contract on the FEP side. Must match
 * {@code baas-card}'s {@code AuthorizationContractShapeTest} (separate modules — the
 * canonical list in {@code docs/contracts/phase1c-interfaces.md} §2a keeps them in sync).
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
        for (RecordComponent rc : AuthorizationDecision.Request.class.getRecordComponents()) {
            actual.put(rc.getName(), rc.getType());
        }
        assertThat(actual).containsExactlyEntriesOf(expected);
    }
}
