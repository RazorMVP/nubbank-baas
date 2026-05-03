package com.nubbank.baas.engine.teller.dto;

import java.util.UUID;

public record CashierRequest(UUID staffId, String description, Boolean isFullDay, String startTime, String endTime) {}
