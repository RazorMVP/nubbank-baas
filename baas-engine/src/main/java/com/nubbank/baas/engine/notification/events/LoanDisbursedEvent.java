package com.nubbank.baas.engine.notification.events;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanDisbursedEvent(UUID loanId, UUID customerId, BigDecimal amount, String schemaName) {}
