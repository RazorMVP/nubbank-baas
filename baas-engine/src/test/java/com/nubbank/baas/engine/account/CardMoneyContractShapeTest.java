package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 16 — engine side of the card↔engine money contract. Pins the record component
 * names + types so a unilateral change here breaks the build. The card module has a mirror test
 * ({@code CardMoneyContractShapeTest}); both assert against the SAME canonical field lists (the
 * modules are separate Maven builds that cannot share one reflection test).
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
    void cardDebitResultShape_isEngineOutcomeEnum() {
        RecordComponent[] c = CardDebitResult.class.getRecordComponents();
        assertThat(c).hasSize(1);
        assertThat(c[0].getName()).isEqualTo("outcome");
        assertThat(c[0].getType()).isEqualTo(CardAuthOutcome.class);
        // The enum names ARE the wire values the card maps to RCs — pin them.
        assertThat(Arrays.stream(CardAuthOutcome.values()).map(Enum::name))
            .containsExactly("DEBITED", "INSUFFICIENT", "ACCOUNT_INVALID", "CURRENCY_MISMATCH");
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
