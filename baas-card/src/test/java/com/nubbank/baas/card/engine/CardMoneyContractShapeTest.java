package com.nubbank.baas.card.engine;

import com.nubbank.baas.card.engine.dto.*;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 16 — card side of the card↔engine money contract. Mirror of the engine's
 * {@code CardMoneyContractShapeTest}; both assert against the SAME canonical field lists.
 *
 * <p>Note: {@code CardDebitResult.outcome} is a {@code String} here (the engine's
 * {@code CardAuthOutcome} enum name on the wire) — the card maps those String values to ISO 8583
 * RCs in {@code AuthorizationDecisionService}.
 */
class CardMoneyContractShapeTest {

    private static String shapeOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
            .map(c -> c.getName() + ":" + c.getType().getSimpleName())
            .reduce((a, b) -> a + "," + b).orElse("");
    }

    @Test
    void cardDebitRequestShape() {
        assertThat(shapeOf(CardDebitRequest.class))
            .isEqualTo("partnerId:String,schemaName:String,accountId:UUID,authKey:String,amount:BigDecimal,currency:String");
    }

    @Test
    void cardDebitResultShape_isStringOutcome() {
        assertThat(shapeOf(CardDebitResult.class)).isEqualTo("outcome:String");
    }

    @Test
    void cardCreditRequestShape() {
        assertThat(shapeOf(CardCreditRequest.class))
            .isEqualTo("partnerId:String,schemaName:String,authKey:String");
    }

    @Test
    void cardCreditResultShape() {
        assertThat(shapeOf(CardCreditResult.class)).isEqualTo("located:boolean");
    }

    @Test
    void accountLookupRequestShape() {
        assertThat(shapeOf(AccountLookupRequest.class))
            .isEqualTo("partnerId:String,schemaName:String,accountId:UUID");
    }

    @Test
    void accountLookupResultShape() {
        assertThat(shapeOf(AccountLookupResult.class))
            .isEqualTo("exists:boolean,active:boolean,currencyCode:String");
    }
}
