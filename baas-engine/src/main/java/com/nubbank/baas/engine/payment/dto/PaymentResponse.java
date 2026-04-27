package com.nubbank.baas.engine.payment.dto;

import com.nubbank.baas.engine.payment.PaymentStatus;
import com.nubbank.baas.engine.payment.PaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    UUID sourceAccountId,
    UUID destinationAccountId,
    BigDecimal amount,
    String currencyCode,
    PaymentType paymentType,
    PaymentStatus status,
    String reference,
    Instant createdAt
) {}
