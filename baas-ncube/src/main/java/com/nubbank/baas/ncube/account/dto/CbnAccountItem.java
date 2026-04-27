package com.nubbank.baas.ncube.account.dto;

import java.util.List;

public record CbnAccountItem(
    String AccountId, String Currency, String AccountType,
    String AccountSubType, String Nickname, List<CbnAccountScheme> Account
) {}
