package com.nubbank.baas.engine.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RescheduleRequest(
    LocalDate rescheduleFromDate,
    BigDecimal newInterestRate,
    Integer graceOnPrincipal,
    Integer graceOnInterest,
    Integer extraTerms,
    Boolean recalculateInterest,
    String reason
) {}
