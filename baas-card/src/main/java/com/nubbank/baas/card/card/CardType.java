package com.nubbank.baas.card.card;

/**
 * Card funding type. Created in Task 3 (card products) and REUSED by Task 4 (card
 * issuance) — do not duplicate.
 *
 * <ul>
 *   <li>{@code DEBIT}   — linked to a deposit account (balance check at auth)</li>
 *   <li>{@code PREPAID} — linked to a prepaid wallet</li>
 *   <li>{@code CREDIT}  — linked to a revolving credit line</li>
 * </ul>
 */
public enum CardType {
    DEBIT,
    PREPAID,
    CREDIT
}
