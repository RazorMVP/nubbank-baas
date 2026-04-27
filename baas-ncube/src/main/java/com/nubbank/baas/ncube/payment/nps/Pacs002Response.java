package com.nubbank.baas.ncube.payment.nps;

/**
 * Parsed pacs.002 Payment Status Report from NPS.
 * ACSC = AcceptedSettlementCompleted (approved), RJCT = Rejected.
 */
public record Pacs002Response(
    String msgId, String orgMsgId, String txStatus, String rejectReason
) {
    public boolean isAccepted() { return "ACSC".equals(txStatus); }
}
