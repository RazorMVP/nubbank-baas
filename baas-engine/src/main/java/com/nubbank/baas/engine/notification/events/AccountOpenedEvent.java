package com.nubbank.baas.engine.notification.events;

import java.util.UUID;

public record AccountOpenedEvent(UUID accountId, UUID customerId, String accountNumber, String schemaName) {}
