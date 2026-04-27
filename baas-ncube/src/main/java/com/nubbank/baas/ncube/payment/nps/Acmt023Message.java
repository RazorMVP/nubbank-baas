package com.nubbank.baas.ncube.payment.nps;

/**
 * Java model for ISO 20022 acmt.023.001.04 (Identification Verification Request).
 * Mandatory Name Enquiry step before every pacs.008 payment.
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md
 */
public record Acmt023Message(
    String msgId, String creDtTm,
    String instgAgtMmbId, String instgAgtBicfi,
    String destMmbId, String institutionName,
    String beneficiaryName, String beneficiaryAcct
) {}
