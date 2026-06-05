package com.nubbank.baas.engine.account;

/** Result of an internal card-authorization debit attempt. Maps to ISO 8583 DE39 on the card side. */
public enum CardAuthOutcome { DEBITED, INSUFFICIENT, ACCOUNT_INVALID, CURRENCY_MISMATCH }
