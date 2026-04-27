package com.nubbank.baas.ncube.consent.dto;

import java.util.List;

public record CbnConsentItem(
    String ConsentId, String CreationDateTime, String Status,
    String StatusUpdateDateTime, List<String> Permissions,
    String ExpirationDateTime, String TPPId, String TPPName
) {}
