package com.nubbank.baas.card.card.dto;

import com.nubbank.baas.card.card.Card;
import com.nubbank.baas.card.card.CardStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Card response — the partner-facing view of a {@link Card}.
 *
 * PAN-SAFETY INVARIANT: this DTO has NO {@code pan} field, NO {@code panEncrypted}
 * field and NO {@code panHash} field. The PAN is exposed ONLY through
 * {@code maskedPan} (e.g. {@code "506000******1234"}).
 */
public record CardResponse(
    UUID id,
    UUID productId,
    String customerRef,
    String maskedPan,
    String expiryYm,
    CardStatus status,
    boolean virtual,
    Instant createdAt
) {
    public static CardResponse from(Card c) {
        return new CardResponse(
            c.getId(),
            c.getProductId(),
            c.getCustomerRef(),
            c.maskedPan(),
            c.getExpiryYm(),
            c.getStatus(),
            c.isVirtual(),
            c.getCreatedAt());
    }
}
