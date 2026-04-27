package com.nubbank.baas.ncube.payment.nps;

/**
 * Parsed acmt.024 (Identification Verification Report) from NPS.
 * nameEnquiryMsgId MUST be included in subsequent pacs.008 SplmtryData.
 */
public record Acmt024Response(
    String msgId, String orgMsgId, String nameEnquiryMsgId,
    String beneficiaryName, String beneficiaryBvn,
    int accountTier, int accountDesignation, boolean verified
) {}
