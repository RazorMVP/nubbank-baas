package com.nubbank.baas.engine.account.dto;

import com.nubbank.baas.engine.account.CardAuthOutcome;

/** Internal card-debit result. The card maps {@code outcome} to ISO 8583 DE39. */
public record CardDebitResult(CardAuthOutcome outcome) {}
