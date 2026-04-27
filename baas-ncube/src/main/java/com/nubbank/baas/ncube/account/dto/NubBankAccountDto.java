package com.nubbank.baas.ncube.account.dto;

import java.math.BigDecimal;

public record NubBankAccountDto(
    String id, String accountNumber, String accountTypeLabel,
    String status, BigDecimal balance, BigDecimal availableBalance, String currencyCode
) {}
