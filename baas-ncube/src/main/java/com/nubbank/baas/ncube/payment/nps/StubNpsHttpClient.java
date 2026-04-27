package com.nubbank.baas.ncube.payment.nps;

import com.nubbank.baas.ncube.common.NcubeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1B stub NPS client returning realistic mock responses.
 * Active when baas.nps.live=false (default).
 * Phase 2: replaced with real NIBSS HTTPS calls using participant credentials.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "baas.nps.live", havingValue = "false", matchIfMissing = true)
public class StubNpsHttpClient implements NpsHttpClient {

    @Override
    public String sendAcmt023(String signedEncryptedXml) throws NcubeException {
        log.debug("STUB sendAcmt023: returning mock acmt.024 VERIFIED response");
        String responseId = "ACMT024-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:acmt.024.001.04\">" +
            "<IdVrfctnRpt><Assgnmt>" +
            "<MsgId>" + responseId + "</MsgId>" +
            "<CreDtTm>" + Instant.now() + "</CreDtTm>" +
            "</Assgnmt>" +
            "<OrgnlAssgnmt><MsgId>STUB-ORIGINAL</MsgId></OrgnlAssgnmt>" +
            "<Vrfctn><Vrfd>true</Vrfd>" +
            "<PtyAndAcctId><Pty><Nm>Stub Beneficiary</Nm></Pty>" +
            "<Acct><Id><IBAN>0581000099</IBAN></Id></Acct></PtyAndAcctId>" +
            "</Vrfctn>" +
            "<SplmtryData><Envlp><CustomData>" +
            "<IdType>bvn</IdType><IdValue>98765432109</IdValue><AccountTier>1</AccountTier>" +
            "</CustomData></Envlp></SplmtryData>" +
            "</IdVrfctnRpt></Document>";
    }

    @Override
    public String sendPacs008(String signedEncryptedXml) throws NcubeException {
        log.debug("STUB sendPacs008: returning mock pacs.002 ACSC (payment approved)");
        String responseId = "PACS002-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.002.001.14\">" +
            "<FIToFIPmtStsRpt><GrpHdr>" +
            "<MsgId>" + responseId + "</MsgId>" +
            "<CreDtTm>" + Instant.now() + "</CreDtTm>" +
            "</GrpHdr>" +
            "<OrgnlGrpInfAndSts><OrgnlMsgId>STUB-ORIGINAL</OrgnlMsgId><GrpSts>ACSC</GrpSts></OrgnlGrpInfAndSts>" +
            "<TxInfAndSts><OrgnlMsgId>STUB-ORIGINAL</OrgnlMsgId><TxSts>ACSC</TxSts></TxInfAndSts>" +
            "</FIToFIPmtStsRpt></Document>";
    }
}
