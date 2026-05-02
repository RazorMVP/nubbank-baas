package com.nubbank.baas.engine.notification.events;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanApprovedEvent(UUID loanId, UUID customerId, BigDecimal amount, String schemaName) {}
