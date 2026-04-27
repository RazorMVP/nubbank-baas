package com.nubbank.baas.ncube.account.dto;

import java.math.BigDecimal;

public record NubBankTransactionDto(
    String id, String transactionType, BigDecimal amount,
    BigDecimal runningBalance, String currencyCode, String reference, String createdAt
) {}
