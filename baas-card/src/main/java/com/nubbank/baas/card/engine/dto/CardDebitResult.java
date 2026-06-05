package com.nubbank.baas.card.engine.dto;

/**
 * Card→engine debit result. {@code outcome} is the engine {@code CardAuthOutcome} name as a
 * String (DEBITED / INSUFFICIENT / ACCOUNT_INVALID / CURRENCY_MISMATCH), or the fail-closed
 * sentinel "UNREACHABLE" when the engine could not be reached.
 */
public record CardDebitResult(String outcome) {}
