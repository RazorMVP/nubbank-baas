package com.nubbank.baas.engine.notification.events;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCompletedEvent(UUID paymentId, UUID sourceAccountId,
    UUID destinationAccountId, BigDecimal amount, String schemaName) {}
