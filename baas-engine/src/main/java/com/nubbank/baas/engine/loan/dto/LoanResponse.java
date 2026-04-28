package com.nubbank.baas.engine.loan.dto;

import com.nubbank.baas.engine.loan.LoanStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

public record LoanResponse(
    UUID id, UUID customerId, UUID loanProductId,
    String loanAccountNumber, BigDecimal principalAmount,
    BigDecimal approvedPrincipal, BigDecimal outstandingBalance,
    BigDecimal interestRate, Integer numberOfRepayments,
    LocalDate disbursementDate, LocalDate maturityDate,
    LoanStatus status, String currencyCode, Instant createdAt
) {}
