package com.nubbank.baas.engine.product.dto;

import com.nubbank.baas.engine.product.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DepositProductResponse(
    UUID id, String name, String shortName, AccountType accountType,
    BigDecimal minimumBalance, BigDecimal nominalInterestRate,
    boolean allowOverdraft, BigDecimal overdraftLimit,
    boolean active, Instant createdAt
) {}
