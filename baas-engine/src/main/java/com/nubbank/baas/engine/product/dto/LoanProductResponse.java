package com.nubbank.baas.engine.product.dto;

import com.nubbank.baas.engine.product.RepaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LoanProductResponse(
    UUID id, String name, String shortName, String description,
    BigDecimal minPrincipal, BigDecimal maxPrincipal, BigDecimal defaultPrincipal,
    BigDecimal nominalInterestRate, RepaymentType repaymentType,
    Integer numberOfRepayments, Integer repaymentEvery, String repaymentFrequency,
    boolean active, Instant createdAt
) {}
