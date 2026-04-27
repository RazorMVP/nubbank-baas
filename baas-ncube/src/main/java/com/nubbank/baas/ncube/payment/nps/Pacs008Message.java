package com.nubbank.baas.ncube.payment.nps;

import java.math.BigDecimal;

/**
 * Java model for ISO 20022 pacs.008.001.12 (FI to FI Customer Credit Transfer).
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md
 */
public record Pacs008Message(
    String msgId, String creDtTm,
    String instgAgtMmbId, String instdAgtMmbId,
    String instrId, String endToEndId, String txId,
    BigDecimal amount, String currency, String settlDt,
    String dbtrName, String dbtrAcct, String dbtrBvn,
    int dbtrAccountTier, int dbtrAccountDesignation,
    String cdtrName, String cdtrAcct, String cdtrBvn,
    int cdtrAccountTier, int cdtrAccountDesignation,
    String narration,
    String nameEnquiryMsgId, String channelCode, String transactionLocation
) {}
