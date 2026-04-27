package com.nubbank.baas.ncube.account.dto;

import com.nubbank.baas.ncube.common.CbnAmount;

public record CbnTransactionItem(
    String AccountId, String TransactionId, String CreditDebitIndicator,
    String Status, String BookingDateTime, CbnAmount Amount, String TransactionInformation
) {}
