package com.nubbank.baas.engine.loan.dto;

import com.nubbank.baas.engine.loan.RepaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ScheduleLineResponse(
    UUID id, Integer installmentNo, LocalDate dueDate,
    BigDecimal principalDue, BigDecimal interestDue, BigDecimal totalDue,
    BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal totalPaid,
    RepaymentStatus status
) {}
