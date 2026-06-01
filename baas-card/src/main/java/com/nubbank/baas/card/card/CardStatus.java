package com.nubbank.baas.card.card;

/**
 * Card lifecycle states. Transitions are guarded by the state machine in
 * {@code CardService} — an illegal transition yields {@code 409 INVALID_TRANSITION}.
 *
 * <pre>
 *   ISSUED  → ACTIVE | CANCELLED
 *   ACTIVE  → BLOCKED | CANCELLED | EXPIRED
 *   BLOCKED → ACTIVE | CANCELLED
 *   CANCELLED → (terminal)
 *   EXPIRED   → (terminal)
 * </pre>
 */
public enum CardStatus {
    ISSUED,
    ACTIVE,
    BLOCKED,
    CANCELLED,
    EXPIRED
}
