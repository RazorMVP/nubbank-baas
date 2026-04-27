package com.nubbank.baas.ncube.account.dto;

import com.nubbank.baas.ncube.common.CbnAmount;

public record CbnBalanceItem(
    String AccountId, String CreditDebitIndicator, String Type,
    String DateTime, CbnAmount Amount
) {}
