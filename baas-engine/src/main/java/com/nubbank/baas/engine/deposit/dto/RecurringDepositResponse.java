package com.nubbank.baas.engine.deposit.dto;

import com.nubbank.baas.engine.deposit.FixedDepositStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

public record RecurringDepositResponse(
    UUID id,
    UUID customerId,
    UUID productId,
    String accountNumber,
    BigDecimal mandatoryInstallment,
    BigDecimal totalDeposited,
    BigDecimal maturityAmount,
    BigDecimal interestRate,
    Integer depositTerm,
    String depositTermUnit,
    LocalDate startDate,
    LocalDate maturityDate,
    FixedDepositStatus status,
    String currencyCode,
    Instant createdAt
) {}
